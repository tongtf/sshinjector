package cn.srv0.sshinjector.domain.vpn

import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「按应用归属 (uid 反查)」可行性探针 (设计附录 A) —— **只读诊断, 不改任何状态**。
 *
 * **2026-10-19 前提更正 (本地 SDK 取证, 推翻设计附录 A 的初判)**:
 * `ConnectivityManager.getConnectionOwnerUid(int, InetSocketAddress, InetSocketAddress)` **不是**
 * 只给系统应用的 `@SystemApi`, 而是**公开 API (自 API 29 起)**:
 * ① 它出现在 `platforms/android-37.0/android.jar` 的公开 stub 里 (`javap` 见 `public int`),
 *    而同 SDK 里 `@SystemApi` 的 `startTethering`/`getTetheringSupported` **不在** stub 中;
 * ② `data/api-versions.xml` 记为 `since="29"`, 且 stub javadoc **明确写给 VPN 应用**:
 *    "uid if the connection is found and the app has permission to observe it (e.g., if it is
 *    associated with the calling VPN app's VpnService tunnel) ... @throws SecurityException if the
 *    caller is not the active VpnService for the current user";
 * ③ annotations 里**没有** `@RequiresPermission` → 不需要清单权限, 只要求"调用者是当前活动 VpnService"。
 *
 * 所以 ④ 的可行性只剩**设备侧行为**待验 (平台实现差异 / 真机是否真的给隧道连接返回归属), 这正是本探针
 * 要变成证据的那一条 —— 设计纪律不变: **验证通过前不把 `appUid` 写进数据模型**。
 *
 * 探针做什么: 在自己进程内建一条**真实**的回环 TCP 连接 (双方都属于本进程), 双向各查一次归属。
 * 返回本进程 uid = "该 API 对我们真的能用" (决定性); 返回 -1 = "能调但这条**环回**连接没有归属"
 * —— 环回不属于任何隧道, -1 是**预期结果**, 所以它**不是**结论 (真机实测: 探针 NO_OWNER 而数据面
 * `UID 归属 命中 7/105` 有命中); SecurityException = "被拒"。判定 ④ 是否可用看**数据面命中率**
 * ([Result.dataPlane])。全程只碰本机回环: 不进隧道、不出网、不影响数据面。
 */
@Singleton
class AppUidProbe
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** 探针结论 (枚举名即语言无关 token, 直接进日志与 UI, 不做本地化 —— 它是机器事实)。 */
        enum class OwnerUid {
            /** 调用成功且**拿到真实归属** (自测回环连接返回本进程 uid) → ④ 可行。 */
            AVAILABLE,

            /**
             * 调用成功但环回连接查不到归属 (-1)。**这不是"功能不可用"的结论**: `getConnectionOwnerUid`
             * 只给"属于调用者 VpnService 隧道"的连接归属, 而探针查的是本进程自己的一条**回环**连接
             * (不属于任何隧道) → -1 是**预期结果** (2026-10-19 真机实测 NO_OWNER, 同期数据面
             * `UID 归属 命中 7/105` 有命中)。判定 ④ 是否可用请看 [Result.dataPlane]。
             */
            NO_OWNER,

            /** 未连接 VPN: 该 API 只对**活动** VpnService 开放, 此刻调用必然被拒 → 直接不跑, 避免误判。 */
            VPN_NOT_ACTIVE,

            /** 公开 SDK 里没有该方法 (理论上不会: 自 API 29 起是公开 API)。 */
            NO_METHOD,

            /** SecurityException: 被拒 (非活动 VpnService / 平台实现差异)。 */
            DENIED,

            /** 其它反射 / 系统异常 (消息进 [Result.detail])。 */
            ERROR,
        }

        data class Result(
            val ownerUid: OwnerUid,
            val usageStatsGranted: Boolean,
            val uidLabelResolvable: Boolean,
            /**
             * 数据面 (真实隧道连接) 的 uid 反查计数: `UID 归属 命中 H/Q 查询 · 无归属 M …`。
             *
             * 这是唯一有判定力的一项 —— 环回探针的 NO_OWNER 是预期结果 (见 [OwnerUid.NO_OWNER]),
             * 而这里 H > 0 就说明平台**确实给隧道连接返回归属**。
             */
            val dataPlane: String,
            val detail: String,
        )

        /**
         * 跑一次探针并把结论落进应用内日志 (无 adb 时这是唯一证据出口), 同时返回给 UI。
         *
         * @param vpnActive 当前是否为**已连接**的 VPN (调用方传 `VpnStatus.Connected`): 该 API 只对活动
         *   VpnService 开放, 未连接时调用必然 `SecurityException` —— 那种"被拒"不是结论, 是误判,
         *   所以直接返回 [OwnerUid.VPN_NOT_ACTIVE]。
         * @param dataPlaneUidStats 数据面反查计数 (`VpnController.appUidDiagnostics()`), 空串 = 没取到。
         */
        fun probe(
            vpnActive: Boolean,
            dataPlaneUidStats: String = "",
        ): Result {
            val (ownerUid, detail) = probeOwnerUid(vpnActive)
            val result =
                Result(
                    ownerUid = ownerUid,
                    usageStatsGranted = usageStatsGranted(),
                    uidLabelResolvable = uidLabelsResolvable(),
                    dataPlane = dataPlaneUidStats,
                    detail = detail,
                )
            VpnController.appLog(
                "UID 归属探针 · getConnectionOwnerUid=${ownerUid.name}" +
                    (if (detail.isEmpty()) "" else " ($detail)") +
                    " · 用量访问=${if (result.usageStatsGranted) "已授权" else "未授权"}" +
                    " · uid 标签=${if (result.uidLabelResolvable) "可解析" else "不可用"}" +
                    (if (dataPlaneUidStats.isEmpty()) "" else " · 数据面 $dataPlaneUidStats"),
                LogLevel.INFO,
            )
            return result
        }

        /**
         * 该方法在**编译期 SDK 里**是否可见 (纯反射, 不需要实例)。
         *
         * 与设备运行时行为无关, 但它是可单测的那一半前提: 单测断言 `true` = "它是公开 API" (自 API 29,
         * 见类注释的取证)。哪天升 compileSdk 后它消失/改名, 这条会红 —— 那正是 ④ 需要重新评估的信号。
         */
        internal fun ownerUidMethodVisible(): Boolean = ownerUidMethod() != null

        private fun ownerUidMethod(): Method? =
            runCatching {
                ConnectivityManager::class.java.getMethod(
                    "getConnectionOwnerUid",
                    Integer.TYPE,
                    InetSocketAddress::class.java,
                    InetSocketAddress::class.java,
                )
            }.getOrNull()

        private fun probeOwnerUid(vpnActive: Boolean): Pair<OwnerUid, String> {
            if (!vpnActive) {
                return OwnerUid.VPN_NOT_ACTIVE to "该 API 只对活动 VpnService 开放, 未连接时调用必然被拒"
            }
            val method = ownerUidMethod() ?: return OwnerUid.NO_METHOD to "SDK 未暴露该方法"
            val connectivity =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return OwnerUid.ERROR to "无 ConnectivityManager"
            return try {
                val myUid = Process.myUid()
                val (forward, backward) = selfQuery(method, connectivity)
                // 只认"返回了本进程 uid"这一种成功: -1 说明 API 能调但给不出归属, 不是 `Available`
                if (forward == myUid || backward == myUid) {
                    OwnerUid.AVAILABLE to "自测回环 uid=$forward/$backward"
                } else {
                    // 环回连接不属于任何隧道 → 平台给 -1 是**预期结果** (不等于隧道连接也拿不到);
                    // 判定 ④ 是否可用看数据面命中率 (Result.dataPlane / 快照资源节)。
                    OwnerUid.NO_OWNER to "自测回环 uid=$forward/$backward, 期望 $myUid (环回非隧道连接, 未归属属预期)"
                }
            } catch (e: InvocationTargetException) {
                classify(e.targetException ?: e)
            } catch (e: Exception) {
                classify(e)
            }
        }

        /**
         * 对自己进程内的一条回环 TCP 连接**双向各查一次**(正向 / 反向), 返回两次结果。
         *
         * 双向是为了消掉"哪一端算 local"这个实现细节上的歧义 (两次都拿不到才算拿不到); 回环双方都是
         * 本进程, 所以两个方向都应归属 [Process.myUid]。
         */
        private fun selfQuery(
            method: Method,
            connectivity: ConnectivityManager,
        ): Pair<Int, Int> =
            ServerSocket(0, LOOPBACK_BACKLOG, InetAddress.getLoopbackAddress()).use { server ->
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                    val local = client.localSocketAddress as InetSocketAddress
                    val remote = client.remoteSocketAddress as InetSocketAddress
                    query(method, connectivity, local, remote) to query(method, connectivity, remote, local)
                }
            }

        private fun query(
            method: Method,
            connectivity: ConnectivityManager,
            local: InetSocketAddress,
            remote: InetSocketAddress,
        ): Int = method.invoke(connectivity, IPPROTO_TCP, local, remote) as? Int ?: Process.INVALID_UID

        private fun classify(t: Throwable): Pair<OwnerUid, String> =
            if (t is SecurityException) {
                OwnerUid.DENIED to (t.message ?: "SecurityException")
            } else {
                OwnerUid.ERROR to "${t.javaClass.simpleName}: ${t.message}"
            }

        /** 用量访问 (app-op) 是否已授权; 未授权 / 服务缺失都算不可用 (绝不抛)。 */
        @Suppress("DEPRECATION") // 公开 API 只剩已被废弃的 String-op 版本; 未废弃的 int-op 常量是 @hide
        private fun usageStatsGranted(): Boolean =
            runCatching {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                appOps != null &&
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName,
                    ) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)

        private fun uidLabelsResolvable(): Boolean =
            runCatching {
                context.packageManager.getPackagesForUid(Process.myUid())?.isNotEmpty() == true
            }.getOrDefault(false)

        private companion object {
            /** `IPPROTO_TCP` (android.system.OsConstants); 6 在 MagicNumber 白名单内。 */
            const val IPPROTO_TCP = 6

            /** 回环自测: 只用于 `connect()` 完成内核握手, 从不 `accept()` (连接已在内核表里即可查询)。 */
            const val LOOPBACK_BACKLOG = 1
        }
    }
