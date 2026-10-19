package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import android.util.Log
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.VpnController
import cn.srv0.sshinjector.domain.vpn.RemoteDomainResolver
import cn.srv0.sshinjector.domain.vpn.RemoteResolveOutcome
import cn.srv0.sshinjector.domain.vpn.SshChannelFactory
import cn.srv0.sshinjector.domain.vpn.TunnelChannel
import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计算 SSH 主机密钥的 SHA-256 指纹 (OpenSSH 标准): "SHA256:<base64>"。
 * 文件级私有函数，KnownHostsManager、KnownHostsHostKeyRepository 与 JschSshClient 共用，避免逻辑重复。
 *
 * OpenSSH 公式: 对**原始 key blob 字节**做 SHA-256, digest 再 base64 (**无填充**) —
 * 与 `ssh-keygen -lf /etc/ssh/ssh_host_*.pub` 输出一致, UI 展示的指纹可与服务器侧带外对账。
 * (旧版曾对 base64(blob) 的 ASCII 做摘要 — 那个格式与 OpenSSH 不互通, 带外核对不可行;
 * 存量 DB/known_hosts 里的旧指纹由 [legacyFingerprintOfBlob] 兼容比对, 新写入一律用本公式。)
 * 用 java.util.Base64 (与 android.util.Base64 NO_WRAP 输出一致)，JVM 单测可直接断言。
 */
private fun computeFingerprint(hostKey: HostKey): String = fingerprintOfBlob(keyBlobOf(hostKey))

/** HostKey.getKey() = 公钥 base64; 非法输入兜底按 ASCII 字节 (仅防御, 生产不会发生)。 */
private fun keyBlobOf(hostKey: HostKey): ByteArray =
    try {
        Base64.getDecoder().decode(hostKey.getKey())
    } catch (_: Exception) {
        hostKey.getKey().toByteArray()
    }

/** SHA-256 指纹 (OpenSSH 标准): raw blob → SHA-256 → base64 无填充。 */
private fun fingerprintOfBlob(blob: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

/** 旧版非标指纹 (SHA256 over base64(blob) ASCII, 带填充): 仅供存量记录兼容比对, 勿用于新写入。 */
private fun legacyFingerprintOfBlob(blob: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(Base64.getEncoder().encodeToString(blob).toByteArray())
    return "SHA256:" + Base64.getEncoder().encodeToString(digest.digest())
}

/** 指纹比对: 新标准格式优先, 兼容旧版非标格式 (升级前入库的指纹)。 */
private fun fingerprintMatches(
    configured: String,
    blob: ByteArray,
): Boolean = configured == fingerprintOfBlob(blob) || configured == legacyFingerprintOfBlob(blob)

/**
 * 单次远程命令执行结果。
 */
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/**
 * SSH Host Key 管理工具
 * 使用 OpenSSH 兼容的 known_hosts 文件格式
 */
@Singleton
class KnownHostsManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val knownHostsFile = File(context.filesDir, "known_hosts")
        private val lock = Any()

        init {
            if (!knownHostsFile.exists()) {
                knownHostsFile.createNewFile()
            }
        }

        /** known_hosts 文件路径（HostKeyRepository.getKnownHostsRepositoryID 用）。 */
        val repositoryId: String
            get() = knownHostsFile.absolutePath

        /**
         * 保存主机密钥到 known_hosts 文件
         */
        fun saveHostKey(
            host: String,
            port: Int,
            hostKey: HostKey,
        ) {
            val fingerprint = computeFingerprint(hostKey)
            val keyType = hostKey.getType()
            // JSch HostKey.getKey() 返回公钥 base64 字符串
            val keyBytes = hostKey.getKey().toByteArray()
            val keyBlob = Base64.getEncoder().encodeToString(keyBytes)
            val line = "$host,$port $keyType $keyBlob $fingerprint\n"

            synchronized(lock) {
                // 移除旧记录
                val lines = knownHostsFile.readText().lines().filter { !it.startsWith("$host,$port ") }
                FileWriter(knownHostsFile).use { writer ->
                    lines.forEach { writer.write("$it\n") }
                    writer.write(line)
                }
            }
            Log.d("KnownHosts", "Saved host key for $host:$port ($fingerprint)")
        }

        /**
         * 删除指定主机的已存记录（服务器重装导致 key 变更时调用）
         * @return true 如果确实存在并删除了记录
         */
        fun removeHostKey(
            host: String,
            port: Int,
        ): Boolean {
            val prefix = "$host,$port "
            synchronized(lock) {
                val lines = knownHostsFile.readText().lines()
                val kept = lines.filter { !it.startsWith(prefix) }
                if (kept.size == lines.size) return false
                FileWriter(knownHostsFile).use { writer ->
                    kept.forEach { writer.write("$it\n") }
                }
            }
            Log.d("KnownHosts", "Removed host key for $host:$port")
            return true
        }

        /**
         * 获取存储的主机指纹
         */
        fun getStoredFingerprint(
            host: String,
            port: Int,
        ): String? {
            val line = findHostLine(host, port)
            return line?.let { extractFingerprint(it) }
        }

        /**
         * 读取已存主机密钥，供 HostKeyRepository.getHostKey 的 @revoked 检查；行损坏返回 null。
         * 行内 key 字段为 base64(base64(blob)) 双重编码（历史格式），解两次还原原始 blob。
         */
        fun getStoredHostKey(
            host: String,
            port: Int,
        ): HostKey? {
            val line = findHostLine(host, port) ?: return null
            val parts = line.split(" ")
            if (parts.size < 3) return null
            return try {
                val blob = Base64.getDecoder().decode(Base64.getDecoder().decode(parts[2]))
                HostKey(host, blob)
            } catch (_: Exception) {
                null
            }
        }

        private fun findHostLine(
            host: String,
            port: Int,
        ): String? = knownHostsFile.readText().lines().firstOrNull { it.startsWith("$host,$port ") }

        private fun extractFingerprint(line: String): String {
            // 格式: host,port keytype base64key SHA256:fingerprint
            return line.split(" ").last()
        }
    }

/**
 * JSch HostKeyRepository 适配器：主机密钥校验发生在 KEX 内、userauth 之前
 * （Session.checkHost，StrictHostKeyChecking=yes 时校验不过 connect() 直接失败）。
 *
 * - 构造时绑定 (host, port)：JSch 对非 22 端口传入 "[host]:port" 形式的 chost，
 *   绑定值规避解析歧义，与 KnownHostsManager 的 "host,port" 键一致。
 * - shkc=yes 下 JSch 对 NOT_INCLUDED 直接抛异常、不会自动 add——TOFU 保存必须在 check() 内完成。
 * - 配置了 hostKeyFingerprint 时指纹为权威比对（匹配才 OK，否则 CHANGED）。
 */
internal class KnownHostsHostKeyRepository(
    private val knownHosts: KnownHostsManager,
    private val host: String,
    private val port: Int,
    private val expectedFingerprint: String?,
    /**
     * 应用内日志出口 (默认 = [VpnController.appLog], 测试可注入捕获)。
     * 首次信任是低频生命周期事件, 用 [VpnController.appLog] 默认键、不加节流。
     */
    private val appLog: (message: String, level: LogLevel) -> Unit = { message, level ->
        VpnController.appLog(message, level)
    },
    private val onFirstTrust: (fingerprint: String) -> Unit = {},
) : HostKeyRepository {
    override fun check(
        chost: String,
        key: ByteArray,
    ): Int {
        // 先验证 blob 结构合法 (HostKey 构造失败 = 畸形密钥, 拒绝而非 TOFU 放行)
        val fingerprint =
            try {
                computeFingerprint(HostKey(host, HostKey.GUESS, key))
            } catch (_: Exception) {
                return HostKeyRepository.NOT_INCLUDED
            }
        val expected = expectedFingerprint
        if (!expected.isNullOrEmpty()) {
            // 兼容旧版非标指纹: 升级前配置的指纹仍按旧公式比对, 避免存量服务器全部 CHANGED
            return if (fingerprintMatches(expected, key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        val stored = knownHosts.getStoredFingerprint(host, port)
        if (stored == null) {
            // TOFU：首连保存并信任；JSch 不会代为 add，必须在本函数内落盘
            saveTofu(key, fingerprint)
            return HostKeyRepository.OK
        }
        return if (fingerprintMatches(stored, key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(
        hostkey: HostKey,
        ui: UserInfo?,
    ) {
        val (h, p) = parseChost(hostkey.getHost())
        knownHosts.saveHostKey(h, p, hostkey)
    }

    override fun remove(
        chost: String,
        type: String,
    ) {
        knownHosts.removeHostKey(host, port)
    }

    override fun remove(
        chost: String,
        type: String,
        key: ByteArray?,
    ) {
        knownHosts.removeHostKey(host, port)
    }

    override fun getKnownHostsRepositoryID(): String = knownHosts.repositoryId

    override fun getHostKey(): Array<HostKey> {
        val key = knownHosts.getStoredHostKey(host, port) ?: return emptyArray()
        return arrayOf(key)
    }

    override fun getHostKey(
        chost: String?,
        type: String?,
    ): Array<HostKey> {
        val key = knownHosts.getStoredHostKey(host, port) ?: return emptyArray()
        return if (type == null || key.getType() == type) arrayOf(key) else emptyArray()
    }

    private fun saveTofu(
        key: ByteArray,
        fingerprint: String,
    ) {
        try {
            knownHosts.saveHostKey(host, port, HostKey(host, HostKey.GUESS, key))
            onFirstTrust(fingerprint)
            // 首连静默 TOFU 的用户可见面: 指纹与服务器 ssh-keygen -lf 输出一致, 可带外核对
            appLog(
                "主机密钥首次信任 · $host:$port — $fingerprint（请与服务器 ssh-keygen -lf 核对）",
                LogLevel.INFO,
            )
        } catch (e: Exception) {
            Log.w("KnownHosts", "TOFU save failed for $host:$port: ${e.message}")
            VpnController.appLogThrottled(
                "主机密钥固定失败 · $host:$port — ${e.message}",
                level = LogLevel.WARNING,
                throttleKey = "主机密钥固定失败",
            )
        }
    }

    /** chost 可能为 "[host]:port"（非 22 端口）——剥壳取裸 host/port，裸名回退绑定端口。 */
    private fun parseChost(chost: String): Pair<String, Int> {
        if (chost.startsWith("[")) {
            val sep = chost.indexOf("]:")
            if (sep > 1) {
                chost.substring(sep + 2).toIntOrNull()?.let { return chost.substring(1, sep) to it }
            }
        }
        return chost to port
    }
}

/**
 * SSH session pool wrapper: one SSH session + its own keepalive + health tracking
 *
 * `internal` (而非 file-private): 供单测构造假条目验证池排空语义 (见 JschSshClientLeakTest)。
 */
internal data class PooledSession(
    val session: Session,
    val scope: CoroutineScope,
    var keepAliveJob: kotlinx.coroutines.Job? = null,
    @Volatile var healthy: Boolean = true,
    /** 重建进行中标志: keepAlive 与通道创建失败可能同时触发同一 session 的重建, CAS 防重入 */
    @Volatile var reconnecting: Boolean = false,
    /** 往返心跳失败标记: 静默黑洞 (单向心跳无感) 的会话级证据, 通道失败时据此判定会话层失效 */
    @Volatile var suspect: Boolean = false,
    var activeChannels: AtomicInteger = AtomicInteger(0),
) {
    /**
     * 最近一次「开通道**超时**」的时刻 (毫秒, 0 = 从未发生)。
     *
     * 必须是 **body 属性而不是构造参数**: data class 的 equals 含全部构造参数, 而
     * `pool.indexOf(pooled)` 靠 equals 定位重建槽位 —— 一个会变化的构造参数会让 indexOf
     * 返回 -1 (被 `coerceAtLeast(0)` 吞成 0), 重建打到错误的槽位上。
     *
     * 用途见 [createDirectChannel]: 开通道超时的会话 `activeChannels` 反而更低,
     * 只按活跃通道数排序会**每次都先挑它** (越坏越优先), 真机日志里表现为
     * 25s 内 3 次 3s 超时反复落在同一批候选上。
     */
    @Volatile var lastOpenStallAtMs: Long = 0L
}

/**
 * `createDirectChannel` 候选循环的预算算术与「坏会话后置」判据 (纯逻辑, 单测锁死)。
 *
 * 为什么要单独抽出来: 曾经把「总预算 / 候选数」**当成总预算**用
 * (`budgetLeft = connectStartedAt + share - now`), 于是首个候选吃满 3s 后预算就耗尽 →
 * `break` → 后面两个健康会话**一次都没试过**, 客户端只等到 `rep=5`。
 * 真机日志里就是 `SSH 通道打开失败 · www.google.com:443 — channel is not opened.` +
 * 3s 后 `SOCKS 连接被拒`, 而同一时刻其它连接的建连只要 60~100ms (会话是好的)。
 */
internal object ChannelOpenBudget {
    /** 「开通道迟滞」后的**后置**时长: 期内该会话排在候选最后 (仍会试, 只是不再优先)。 */
    const val STALL_DEMOTE_MS = 30_000L

    /**
     * 单候选 `channel.connect` 的超时 (毫秒) = `min(候选份额, 剩余总预算, 硬上限)`。
     * 返回 `<= 0` 表示总预算已耗尽, 调用方**必须停止**尝试后续候选。
     */
    fun attemptTimeoutMs(
        deadlineAtMs: Long,
        nowMs: Long,
        shareMs: Long,
        maxMs: Long,
    ): Long = minOf(maxMs, shareMs, deadlineAtMs - nowMs)

    /** 最近 [STALL_DEMOTE_MS] 内发生过开通道超时 → 排序后置 (`lastStallAtMs == 0` = 从未)。 */
    fun isRecentlyStalled(
        lastStallAtMs: Long,
        nowMs: Long,
    ): Boolean = lastStallAtMs > 0L && nowMs - lastStallAtMs < STALL_DEMOTE_MS
}

@Singleton
class JschSshClient
    @Inject
    constructor(
        private val keyManager: SshKeyManager,
        private val knownHostsManager: KnownHostsManager,
        private val serverDao: ServerDao,
    ) : SshChannelFactory,
        RemoteCommandExecutor,
        RemoteDomainResolver {
        companion object {
            private const val TAG = "JschSshClient"
            private const val SESSION_POOL_SIZE = 3

            /** 每 N 次 keepAlive 做一次**往返**心跳 (约 N × 30s)。 */
            private const val ROUND_TRIP_HEARTBEAT_EVERY = 6

            /** 保活周期下限 (秒): 挡住 delay(0)/delay(sub-second) 自旋, 0 由 startSessionKeepAlive 视为关闭。 */
            private const val MIN_KEEPALIVE_INTERVAL_SECONDS = 1L

            /** 往返心跳 exec 通道的 connect 超时 (毫秒)。 */
            private const val HEARTBEAT_EXEC_TIMEOUT_MS = 5000

            /**
             * 往返探活三态 (见 [verifySessionRoundTrip])。
             * UNAVAILABLE 必须与 FAILED 分开: 前者是服务器禁用 exec, 会话是好的。
             * (internal 供单测断言三态语义。)
             */
            internal const val ROUND_TRIP_OK = 0
            internal const val ROUND_TRIP_UNAVAILABLE = 1
            internal const val ROUND_TRIP_FAILED = 2

            /**
             * 探活耗时低于此值仍失败 → 判为「服务器拒绝 exec 通道」而非黑洞。
             * 取 2.5s (1/2 的 exec 超时): 正常 RTT + 通道开销远小于此, 黑洞则会撑满超时。
             * 宁可保守 —— 误判成 UNAVAILABLE 只是丢一个探活手段, 误判成 FAILED 会引爆整池重建。
             */
            private const val ROUND_TRIP_SLOW_FAIL_MS = 2500L

            private const val CHANNEL_WINDOW_SIZE = 8 * 1024 * 1024

            /** 远端解析 exec 的输出上限 (防服务器灌爆内存; getent 的正常输出只有几百字节)。 */
            private const val REMOTE_RESOLVE_MAX_OUTPUT = 64 * 1024

            /** 远端解析 exec 的关闭轮询间隔 (阻塞线程, 与回程中继的刻意 sleep 同性质)。 */
            private const val REMOTE_RESOLVE_POLL_MS = 20L

            /**
             * JSch 每条 Session 只有**一个读线程** (`Session.run`), 它把 CHANNEL_DATA
             * 直接写进通道 `getInputStream()` 的 `PipedInputStream`, 而该管道默认只有
             * 32KB 且**不可增长** (`resizable = 32KB < max_input_buffer_size`, 默认值同为 32KB)。
             * 管道一满 `PipedOutputStream.write` 就把读线程阻塞住 —— 这条会话的
             * WINDOW_ADJUST、其它通道的数据、OPEN_CONFIRMATION **全部停摆**,
             * 而写方向完全正常 (走的是 session 的 socket, 与该管道无关)。
             * 现场形态: 多个通道同时「已写 517B / 读回 0B」、新通道 `connect` 慢 1~2.5s、
             * 浏览正常但下载卡"等待中"。放大到 1MB 让管道可增长, 单个慢消费者
             * (TUN 写被阻塞的 `TunSink.writeBlocking` 卡住) 不再 32KB 就冻住整条会话。
             */
            private const val MAX_INPUT_BUFFER_SIZE = "1048576"
            private const val CHANNEL_SEND_MAX_PACKET_SIZE = 64 * 1024
            private const val CHANNEL_CONNECT_TIMEOUT_MS = 10000

            /**
             * `createDirectChannel` 全部候选**共享**的总预算。
             *
             * `Socks5ProxyServer` 在 Connecting 状态 10s 就关连接 (`connectionTimeoutMs`),
             * 而单候选也是 10s —— 3 候选 ×10s = 30s, 首个候选超时时代理早已关闭, 后续候选
             * 全是白跑 + 白占 `SshIoDispatcher` 线程, "逐个重试"实际不起作用。
             * 留 1s 余量给 loopback connect + SOCKS 协商, 保证在代理关连接前收尾。
             */
            private const val CHANNEL_BUDGET_MS = 9000L

            /**
             * 单候选的预算份额 = 总预算 / 会话数。
             *
             * 两个数缺一不可: 只有总预算 (9s) 时首个候选会把 9s 全吃掉, 后两个候选一点时间
             * 都拿不到; 只有份额 (3s) 时总预算变成 3s, 首候选超时后循环直接 `break` ——
             * 后者就是真机日志里"3 个健康会话, 却只试了 1 个"的成因
             * (见 [ChannelOpenBudget])。
             */
            private const val CHANNEL_BUDGET_PER_CANDIDATE_MS = CHANNEL_BUDGET_MS / SESSION_POOL_SIZE

            /**
             * 「开通道迟滞」判据 (毫秒): 单次候选尝试耗时超过它就记一次 stall, 该会话在
             * 后续选择中后置。取 1s —— 服务器直接回 `OPEN_FAILURE` (目标不可达) 是 RTT 级
             * (几十~几百 ms), 只有"等不到 `OPEN_CONFIRMATION`"才可能拖到秒级。
             */
            private const val OPEN_STALL_MIN_MS = 1000L

            // 通道健康统计 (排障唯一可见面): 区分「目标层拒绝」与「会话层失效」是本次雪崩 bug 的关键,
            // 只看单条错误日志无法判断是远端连不上目标还是 SSH 会话坏了。
            private val statChannelOk = AtomicLong()
            private val statChannelTargetFail = AtomicLong()
            private val statChannelSessionFail = AtomicLong()
            private val statReconnectOk = AtomicLong()
            private val statReconnectFail = AtomicLong()
            private val statLastSnapshotAt = AtomicLong()
            private const val STAT_SNAPSHOT_INTERVAL_MS = 300_000L

            // S7 算法白名单。注意 JSch 0.2.x 的 session 配置键是 kex/server_host_key/cipher.c2s/mac.c2s,
            // 写 KexAlgorithms/HostKeyAlgorithms/Cipher/MAC 不会被 Session 读取 (静默 no-op)。
            internal const val KEX_ALGORITHMS =
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                    "diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha256"
            internal const val HOST_KEY_ALGORITHMS =
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
                    "rsa-sha2-512,rsa-sha2-256"
            internal const val PUBKEY_ACCEPTED_ALGORITHMS =
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
                    "rsa-sha2-512,rsa-sha2-256"
            internal const val CIPHER_ALGORITHMS =
                "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes256-ctr"
            internal const val MAC_ALGORITHMS =
                "hmac-sha2-512-etm@openssh.com,hmac-sha2-256-etm@openssh.com,hmac-sha2-512,hmac-sha2-256"

            /** KEX/主机密钥/公钥/Cipher/MAC 白名单 (createSession 与 execSingleShot 共用)。 */
            internal fun applyAlgorithmWhitelist(s: Session) {
                s.setConfig("kex", KEX_ALGORITHMS)
                s.setConfig("server_host_key", HOST_KEY_ALGORITHMS)
                s.setConfig("PubkeyAcceptedAlgorithms", PUBKEY_ACCEPTED_ALGORITHMS)
                // GCM(AEAD)优先, 兼容旧服务器用 CTR; 不含 CBC (无认证加密, BEAST 类攻击面)
                s.setConfig("cipher.c2s", CIPHER_ALGORITHMS)
                s.setConfig("cipher.s2c", CIPHER_ALGORITHMS)
                s.setConfig("mac.c2s", MAC_ALGORITHMS)
                s.setConfig("mac.s2c", MAC_ALGORITHMS)
            }

            /**
             * JSch Channel 的窗口/包大小 setter 为包内可见, 只能反射调用。
             * 每个 setter 只解析一次并缓存, 避免每连接遍历 declaredMethods。
             */
            private val CHANNEL_METHODS: List<java.lang.reflect.Method> by lazy {
                com.jcraft.jsch.Channel::class.java.declaredMethods
                    .filter {
                        it.name == "setLocalWindowSizeMax" ||
                            it.name == "setLocalWindowSize" ||
                            it.name == "setLocalPacketSize"
                    }.map {
                        it.isAccessible = true
                        it
                    }
            }

            @Volatile private var loggerSet = false
        }

        private val pool = ConcurrentLinkedQueue<PooledSession>()
        private val sessionIndex = AtomicInteger(0)

        @Volatile private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val isConnectedFlag = AtomicBoolean(false)
        private var currentConfig: ServerConfig? = null

        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

        enum class ConnectionState {
            Disconnected,
            Connecting,
            Authenticating,
            EstablishingTunnel,
            Connected,
            Disconnecting,
            Failed,
        }

        override suspend fun connect(config: ServerConfig): SshChannelFactory.ConnectionResult {
            if (isConnectedFlag.get()) {
                return SshChannelFactory.ConnectionResult(false, error = "Already connected")
            }

            // 兜底排空: checkPoolHealth() 会在不排空池的情况下把 isConnectedFlag 置 false
            // (池失败 → Failed 状态), 之后若走 connect 而非 disconnect, 旧 Session/Scope 会与新会话叠加。
            // connect 由 SshVpnService 的 connectMutex 串行, 此处再排空一次不引入新的并发风险。
            drainPool()

            connectionState.value = ConnectionState.Connecting
            currentConfig = config

            return try {
                // 并行建连: 3 条 session 彼此无依赖, 串行会把 3 次 TCP+SSH 握手的 RTT
                // 全部串进连接耗时 (实测建连 1.6~4s 的主要来源之一)。失败容忍逻辑不变。
                val results =
                    coroutineScope {
                        (0 until SESSION_POOL_SIZE)
                            .map { i ->
                                async(Dispatchers.IO) { i to createSession(config, i) }
                            }.awaitAll()
                    }
                var successCount = 0
                for ((i, pooled) in results) {
                    if (pooled != null) {
                        pool.add(pooled)
                        successCount++
                        Log.d(TAG, "Session pool [$i/$SESSION_POOL_SIZE] connected")
                    } else {
                        Log.w(TAG, "Session pool [$i/$SESSION_POOL_SIZE] failed")
                    }
                }

                if (successCount == 0) {
                    throw Exception("All $SESSION_POOL_SIZE SSH sessions failed to connect")
                }
                if (successCount < SESSION_POOL_SIZE) {
                    // 部分失败不触发 handleError (状态仍 Connected), 但可用会话下降、
                    // 建连变慢 —— 只打 logcat 用户无从知晓, 必须进应用日志
                    VpnController.appLog(
                        "SSH 会话池部分失败 · $successCount/$SESSION_POOL_SIZE 条可用 — 建连可能变慢",
                        level = LogLevel.WARNING,
                    )
                }

                isConnectedFlag.set(true)
                connectionState.value = ConnectionState.Connected

                Log.d(TAG, "Session pool ready: $successCount/$SESSION_POOL_SIZE sessions active")
                SshChannelFactory.ConnectionResult(true)
            } catch (e: JSchException) {
                handleError("SSH connection failed: ${e.message}")
                SshChannelFactory.ConnectionResult(false, error = e.message)
            } catch (e: Exception) {
                handleError("Unexpected error: ${e.message}")
                SshChannelFactory.ConnectionResult(false, error = e.message)
            }
        }

        private fun createSession(
            config: ServerConfig,
            index: Int,
        ): PooledSession? {
            var session: Session? = null
            return try {
                val jsch = JSch()
                // JSch.setLogger 是全局静态，只设置一次
                synchronized(JSch::class.java) {
                    if (!loggerSet) {
                        JSch.setLogger(
                            object : com.jcraft.jsch.Logger {
                                private val levels =
                                    mapOf(
                                        com.jcraft.jsch.Logger.DEBUG to "DEBUG",
                                        com.jcraft.jsch.Logger.INFO to "INFO",
                                        com.jcraft.jsch.Logger.WARN to "WARN",
                                        com.jcraft.jsch.Logger.ERROR to "ERROR",
                                        com.jcraft.jsch.Logger.FATAL to "FATAL",
                                    )

                                override fun isEnabled(level: Int) = level >= com.jcraft.jsch.Logger.WARN

                                override fun log(
                                    level: Int,
                                    message: String,
                                ) {
                                    if (level >= com.jcraft.jsch.Logger.WARN) {
                                        Log.w(TAG, "[${levels[level] ?: level}] $message")
                                    }
                                }
                            },
                        )
                        loggerSet = true
                    }
                }
                val keyAdded = keyManager.createJSchIdentity(jsch, config.keyAlias)
                if (!keyAdded) {
                    throw Exception("无法访问私钥")
                }

                val s = jsch.getSession(config.username, config.host, config.port)
                session = s
                if (!config.password.isNullOrEmpty()) {
                    val passwordBytes = config.password.toByteArray(Charsets.UTF_8)
                    try {
                        // JSch setPassword(byte[]) 内部会 clone，之后本地字节数组可安全清零
                        s.setPassword(passwordBytes)
                    } finally {
                        java.util.Arrays.fill(passwordBytes, 0)
                    }
                }
                // 主机密钥校验在 KEX 内完成（认证前拦截）；TOFU 首次保存后指纹落库，下次连接即强校验
                s.setHostKeyRepository(
                    KnownHostsHostKeyRepository(
                        knownHostsManager,
                        config.host,
                        config.port,
                        config.hostKeyFingerprint,
                    ) { fp ->
                        persistFingerprint(config, fp)
                    },
                )
                s.setConfig("StrictHostKeyChecking", "yes")
                s.setConfig("TCPNoDelay", "yes") // 禁用 Nagle, 降低 SSH 小包 (ACK/交互) 的 RTT
                // 必须在任何 getInputStream() 之前: 该配置在建流时读取 (见常量注释)
                s.setConfig("max_input_buffer_size", MAX_INPUT_BUFFER_SIZE)
                s.setConfig("PreferredAuthentications", "publickey,password")
                s.setConfig("PubkeyAuthentication", "yes")
                s.setConfig("PasswordAuthentication", "yes")
                // KEX/主机密钥/Cipher/MAC 白名单 (移除 group1-sha1/ssh-dss/CBC/hmac-sha1)
                applyAlgorithmWhitelist(s)
                s.setTimeout(config.connectTimeout)
                // 注意: 绝不要用 mwiede JSch 的 setServerAliveInterval/setServerAliveCountMax ——
                // 0.2.17 里这两个字段只写不读 (功能未实现), 而 setServerAliveInterval 内部会顺手
                // setTimeout(同值): 传 30 (秒) 会把 socket 超时改成 30ms, 握手必失败
                // ("timeout: socket is not established")。往返探活由自建的 exec 心跳实现, 见 startSessionKeepAlive。

                connectionState.value = ConnectionState.Authenticating
                s.connect()

                val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val pooled = PooledSession(session = s, scope = sessionScope)
                startSessionKeepAlive(pooled, config.keepAliveInterval.toLong(), index)

                pooled
            } catch (e: Exception) {
                // 校验/连接失败的 Session 必须断开，否则 socket + 线程泄漏
                try {
                    session?.disconnect()
                } catch (_: Exception) {
                }
                // 带堆栈记录：暴露 JSch KEX/主机密钥/鉴权的真实异常链，便于区分握手阶段失败原因
                Log.e(TAG, "createSession[$index] failed: ${e.message}", e)
                VpnController.appLogThrottled(
                    "SSH 会话创建失败 · pool-$index — ${e.message}",
                    level = LogLevel.ERROR,
                    throttleKey = "SSH 会话创建失败",
                )
                null
            }
        }

        /** TOFU 首次保存后指纹落库（id=0 的临时 config 如 provisioning 跳过）。 */
        private fun persistFingerprint(
            config: ServerConfig,
            fingerprint: String,
        ) {
            if (config.id <= 0) return
            try {
                serverDao.updateHostKeyFingerprint(config.id, fingerprint)
            } catch (e: Exception) {
                Log.w(TAG, "updateHostKeyFingerprint failed: ${e.message}")
                VpnController.appLogThrottled(
                    "主机指纹保存失败 · ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "主机指纹保存失败",
                )
            }
        }

        /**
         * @param intervalSeconds keepAlive 周期, **单位秒** (ServerEntity/UI 均为秒)。
         * 历史 bug: 曾把秒值直接当毫秒 delay(30), 每 30ms 发一个 SSH 心跳 ×3 会话,
         * 持续高频小包约 20s 后被中间设备/服务器 RST, 表现为"连接建立后 ~20s 三个会话批量断"。
         */
        private fun startSessionKeepAlive(
            pooled: PooledSession,
            intervalSeconds: Long,
            index: Int,
        ) {
            // 0 = 用户关闭保活 (UI 允许 0-3600 秒)。不加这道门会 delay(0) 忙循环:
            // 协程满速发 sendKeepAliveMsg, 3 条会话一起把 CPU 和 SSH 通道刷爆。
            if (intervalSeconds <= 0L) {
                Log.i(TAG, "Session pool-$index: keepAlive disabled (interval=$intervalSeconds)")
                // 文案不带 pool-$index: appLogThrottled 以整条 message 为节流 key, 动态段会让节流失效
                VpnController.appLogThrottled("SSH 保活已关闭 · 间隔 0 秒")
                pooled.keepAliveJob = null
                return
            }
            // 防御: 即便上游把 sub-second/0 传进来, 也绝不能进入 delay(0) 自旋
            val delayMs = intervalSeconds.coerceAtLeast(MIN_KEEPALIVE_INTERVAL_SECONDS) * 1000L
            pooled.keepAliveJob =
                pooled.scope.launch {
                    var tick = 0
                    while (isActive && isConnectedFlag.get()) {
                        delay(delayMs)
                        if (!isActive) break
                        try {
                            if (pooled.session.isConnected) {
                                pooled.session.sendKeepAliveMsg()
                                // 不在此把 healthy 置回 true: 静默黑洞下 sendKeepAliveMsg 本地写恒成功,
                                // 置 true 会抵消通道失败路径的 unhealthy 标记, 让黑洞会话被持续优先选中。
                                // healthy 只由「重连成功」恢复; 会话真死时 isConnected=false → 走下方分支。
                                //
                                // 每 N 跳做一次**往返**心跳 (exec 'true' = POSIX 内置 no-op, 不 fork 进程):
                                // 单向心跳在静默黑洞下永远"成功", 只有真正收发一次才能发现黑洞。
                                // 失败只标记 suspect, 不直接重建 —— 服务器禁用 exec 时不误杀健康会话;
                                // 真正的重建由「通道失败 + suspect」判定触发 (见 createDirectChannel)。
                                tick++
                                if (tick % ROUND_TRIP_HEARTBEAT_EVERY == 0) {
                                    when (verifySessionRoundTrip(pooled)) {
                                        ROUND_TRIP_OK -> {
                                            if (pooled.suspect) {
                                                VpnController.appLogThrottled(
                                                    "SSH 会话往返恢复 · pool-$index",
                                                    level = LogLevel.INFO,
                                                    throttleKey = "SSH 会话往返恢复",
                                                )
                                                pooled.suspect = false
                                            }
                                        }
                                        ROUND_TRIP_FAILED -> {
                                            pooled.suspect = true
                                            VpnController.appLogThrottled(
                                                "SSH 会话往返心跳超时 · pool-$index — 标记可疑(仅通道也失败才重建)",
                                                level = LogLevel.WARNING,
                                                throttleKey = "SSH 会话往返心跳超时",
                                            )
                                        }
                                        else -> {
                                            // ROUND_TRIP_UNAVAILABLE: 服务器禁用 exec, 秒内 channel-open-failure。
                                            // **不得**标 suspect —— 否则 exec 受限的服务器上 suspect 常驻为 true,
                                            // 任意一次正常的「远端连不上目标」都会被判成 sessionDead → 整池重建
                                            // → 「重建窗口内新连接全失败 → 再重建」雪崩, 下载全挂 (见 createDirectChannel)。
                                            Log.d(
                                                TAG,
                                                "Session pool-$index: exec probe unsupported",
                                            )
                                        }
                                    }
                                }
                            } else {
                                Log.w(TAG, "Session pool-$index: not connected, attempting reconnect")
                                VpnController.appLogThrottled(
                                    "SSH 会话 pool-$index 未连接, 尝试重连",
                                    level = LogLevel.WARNING,
                                    throttleKey = "SSH 会话未连接",
                                )
                                pooled.healthy = false
                                tryReconnectSession(pooled, index)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // 协程被 cancel (断开/重建) 是正常退出, 不能当 keepAlive 故障去触发重连:
                            // 否则用户正常断开时会打出 ERROR「已耗尽所有重试」、污染 statReconnectFail,
                            // checkPoolHealth() 还可能把 Failed 盖到 Disconnected 上。
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Session pool-$index: keepAlive failed, attempting reconnect", e)
                            VpnController.appLogThrottled(
                                "SSH 会话 pool-$index keepAlive 失败, 尝试重连",
                                level = LogLevel.WARNING,
                                throttleKey = "SSH keepAlive 失败",
                            )
                            pooled.healthy = false
                            tryReconnectSession(pooled, index)
                        }
                    }
                }
        }

        /**
         * 每 5 分钟打一条通道/重连统计快照 (应用内日志是唯一可见面)。
         * 目标层拒绝 vs 会话层失效的比例能一眼区分"远端连不上目标"与"SSH 隧道整体失效"。
         */
        private fun maybeLogChannelStats(lastHost: String) {
            val now = System.currentTimeMillis()
            val last = statLastSnapshotAt.get()
            if (now - last < STAT_SNAPSHOT_INTERVAL_MS || !statLastSnapshotAt.compareAndSet(last, now)) return
            VpnController.appLog(
                "SSH 通道统计 · 成功 ${statChannelOk.get()} / 目标层失败 ${statChannelTargetFail.get()} " +
                    "/ 会话层失败 ${statChannelSessionFail.get()} · 重连成功 ${statReconnectOk.get()} " +
                    "/ 重连失败 ${statReconnectFail.get()} · 池 ${pool.size} 会话 (最近目标 $lastHost)",
                level = LogLevel.INFO,
            )
        }

        /**
         * 往返心跳: 开一个 exec 通道跑 `true` (POSIX shell 内置, 不 fork 进程, 服务器开销可忽略),
         * 等 exit-status 回来, 验证会话真的能双向收发。
         * 单向 sendKeepAliveMsg 在静默黑洞下本地写恒成功, 只有真正往返才能发现黑洞。
         * 失败/超时都只标记 [PooledSession.suspect], 不直接重建 —— 服务器禁用 exec 或命令卡住时
         * 不能误杀健康会话; 真正的重建由「通道失败 + suspect」判定触发 (见 createDirectChannel)。
         *
         * @return [ROUND_TRIP_OK] 成功; [ROUND_TRIP_FAILED] 超时/黑洞 (→ 标 suspect);
         *   [ROUND_TRIP_UNAVAILABLE] 服务器禁用 exec, **秒内**被 channel-open-failure 拒绝。
         *   UNAVAILABLE 绝不能算失败: exec 受限的服务器上若照常标 suspect, suspect 会常驻为 true,
         *   于是任意一次正常的「远端连不上目标」都凑成 `sessionDead` → 整池重建雪崩。
         */
        internal suspend fun verifySessionRoundTrip(pooled: PooledSession): Int {
            val session = pooled.session
            val probe =
                pooled.scope.async<Int>(Dispatchers.IO) {
                    var ch: com.jcraft.jsch.ChannelExec? = null
                    val startedAt = System.currentTimeMillis()
                    try {
                        ch = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                        ch.setCommand("true")
                        ch.setInputStream(null)
                        ch.setOutputStream(java.io.ByteArrayOutputStream())
                        ch.setErrStream(java.io.ByteArrayOutputStream())
                        ch.connect(HEARTBEAT_EXEC_TIMEOUT_MS)
                        // connect 能等到通道建立并收到收发 = 真正的往返证据 (exit-status 不必等,
                        // 'true' 在部分受限 shell 上拿不到, 但通道能开就足以证明会话活着)
                        ROUND_TRIP_OK
                    } catch (e: Exception) {
                        Log.w(TAG, "round-trip heartbeat failed: ${e.message}")
                        // 撑到慢失败阈值 = 服务器没回应 (黑洞); 秒级被拒 = 服务器不支持 exec
                        val stalled = System.currentTimeMillis() - startedAt >= ROUND_TRIP_SLOW_FAIL_MS
                        if (stalled || e.message?.contains("timeout", ignoreCase = true) == true) {
                            ROUND_TRIP_FAILED
                        } else {
                            ROUND_TRIP_UNAVAILABLE
                        }
                    } finally {
                        // 成功/异常两条路径都必须释放 exec 通道, 否则通道残留在会话上 (JSch 静态 pool 泄漏)
                        runCatching { ch?.disconnect() }
                    }
                }
            return awaitProbeOrCancel(probe, HEARTBEAT_EXEC_TIMEOUT_MS * 2L)
        }

        /**
         * 等一个阻塞探活/查询的结果: 超时返回 **null**, 且无论成功/超时都取消未完成的协程。
         *
         * exec/查询阻塞在 socket 上, `withTimeout` 中断不了阻塞调用, 但必须取消 `probe` (Job 取消可打断
         * 挂起中的等待) 避免协程句柄长期留存; 阻塞的 IO 线程在 socket 超时后自会结束。
         * 抽为独立函数便于单测用短超时锁死"超时必取消"语义 (无需等真实 10s 超时)。
         */
        internal suspend fun <T> awaitOrCancel(
            probe: Deferred<T>,
            timeoutMs: Long,
        ): T? =
            try {
                withTimeoutOrNull(timeoutMs) { probe.await() }
            } finally {
                if (!probe.isCompleted) probe.cancel()
            }

        /** 往返探活版本: 超时归 [ROUND_TRIP_FAILED]。 */
        internal suspend fun awaitProbeOrCancel(
            probe: Deferred<Int>,
            timeoutMs: Long,
        ): Int = awaitOrCancel(probe, timeoutMs) ?: ROUND_TRIP_FAILED

        /**
         * 远端解析 (域名统计「使用远端解析」开关): 复用**池内已有会话**的 exec 通道跑
         * `getent ahosts <域名>`, 拿到 VPS 出口实际会连的 IP (设计 §7.2)。
         *
         * **绝不用** `execSingleShot`: 那个实现每次 `getSession()+connect()` 建**一整条新 SSH 会话**
         * (0.4~4s), 且会在服务器侧堆积会话 (AGENTS 记录过"累积会话 → SSH connect 超时")。
         * 也不在 `SshIoDispatcher` 上跑 —— 那个专用池只服务数据面阻塞读写。
         *
         * 三态 (见 [RemoteResolveOutcome]): 秒级被拒 = 服务器禁用 exec → 换个会话再试, 全都快失败才
         * Unavailable; 撑满预算 = 黑洞 → 立刻 Failed (不拿别的会话陪跑)。**任何异常都不外抛**,
         * 调用方一律回退本地视角且不报错。
         *
         * 纯观测动作: 不开 direct-tcpip、不碰隧道状态、不进 DNS 缓存。
         */
        override suspend fun resolveIps(
            domain: String,
            timeoutMs: Long,
        ): RemoteResolveOutcome {
            // 域名来自 DNS 应答 (也就是网络, 不可信): 不合法就绝不拼进命令 (命令注入防护)
            if (!RemoteLookup.isValidHost(domain)) {
                Log.w(TAG, "resolveIps: rejected unsafe host")
                return RemoteResolveOutcome.Unavailable
            }
            if (pool.isEmpty()) return RemoteResolveOutcome.Unavailable
            val now = System.currentTimeMillis()
            val candidates =
                pool
                    .toList()
                    .filter { it.session.isConnected && it.healthy }
                    // 与 createDirectChannel 同一判据: 刚出过问题的会话排最后, 别在它身上反复烧预算
                    .sortedWith(
                        compareBy(
                            { ChannelOpenBudget.isRecentlyStalled(it.lastOpenStallAtMs, now) },
                            { it.activeChannels.get() },
                        ),
                    )
            if (candidates.isEmpty()) return RemoteResolveOutcome.Unavailable

            val deadlineAt = System.currentTimeMillis() + timeoutMs
            val command = RemoteLookup.command(domain)
            var fastRejected = false
            for (pooled in candidates) {
                val left = deadlineAt - System.currentTimeMillis()
                if (left <= 0) break
                val probe = pooled.scope.async(Dispatchers.IO) { runExecLookup(pooled, command, left) }
                when (val outcome = awaitOrCancel(probe, left)) {
                    null -> return RemoteResolveOutcome.Failed // 撑满预算没回 = 黑洞
                    is RemoteResolveOutcome.Ok -> return outcome
                    RemoteResolveOutcome.Unavailable -> fastRejected = true
                    RemoteResolveOutcome.Failed -> return RemoteResolveOutcome.Failed
                }
            }
            return if (fastRejected) RemoteResolveOutcome.Unavailable else RemoteResolveOutcome.Failed
        }

        /**
         * 一次 exec 查询 (**阻塞**: 只允许在 `Dispatchers.IO` 上跑, 与 [verifySessionRoundTrip] 一致)。
         *
         * 读输出用 `setOutputStream(有界流)`: JSch 的读线程直接写进我们的流。**不能**
         * `setOutputStream(null)` 后再手工读 —— 那会走通道默认的 32KB `PipedOutputStream`, 我们读慢一点
         * 就阻塞会话的**唯一读线程**, 整条 SSH 会话 (含所有数据通道) 一起冻结 (AGENTS「JSch 一条 Session
         * 只有一个读线程」)。
         */
        private fun runExecLookup(
            pooled: PooledSession,
            command: String,
            timeoutMs: Long,
        ): RemoteResolveOutcome {
            var channel: ChannelExec? = null
            val startedAt = System.currentTimeMillis()
            return try {
                channel = pooled.session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                channel.setInputStream(null)
                val sink = BoundedSink(REMOTE_RESOLVE_MAX_OUTPUT)
                channel.setOutputStream(sink)
                channel.setErrStream(ByteArrayOutputStream())
                channel.connect(timeoutMs.toInt())
                val closeAt = System.currentTimeMillis() + timeoutMs
                while (!channel.isClosed && System.currentTimeMillis() < closeAt) {
                    Thread.sleep(REMOTE_RESOLVE_POLL_MS)
                }
                val exitStatus = channel.exitStatus
                val ips = RemoteLookup.parseGetent(sink.text())
                val elapsedMs = System.currentTimeMillis() - startedAt
                if (ips.isEmpty()) {
                    // 空输出 = getent 不存在 (exit 127) / 无记录 —— 都算"远端给不出答案", 回退本地
                    Log.i(TAG, "remote resolve empty: exit=$exitStatus elapsed=${elapsedMs}ms")
                    RemoteResolveOutcome.Unavailable
                } else {
                    RemoteResolveOutcome.Ok(
                        ips = ips,
                        elapsedMs = elapsedMs,
                        sessionId = "S" + Integer.toHexString(System.identityHashCode(pooled.session)),
                    )
                }
            } catch (e: Exception) {
                // 与往返探活同一判据: 撑到慢失败阈值 = 服务器没回应 (黑洞); 秒级被拒 = 不支持 exec
                val stalled = System.currentTimeMillis() - startedAt >= ROUND_TRIP_SLOW_FAIL_MS
                Log.w(TAG, "remote resolve failed (stalled=$stalled): ${e.message}")
                if (stalled) RemoteResolveOutcome.Failed else RemoteResolveOutcome.Unavailable
            } finally {
                // 成功/异常都必须断开: exec 通道残留在 JSch 静态 Channel.pool 里 = 泄漏 + 持续灌管道
                runCatching { channel?.disconnect() }
            }
        }

        /**
         * 有上限的输出流 (JSch 读线程写, 我们的 IO 线程读 → 必须同步)。
         * 超过上限直接丢弃多余字节 (正常 `getent` 输出只有几百字节; 上限挡的是被灌爆内存)。
         */
        private class BoundedSink(
            private val limit: Int,
        ) : java.io.OutputStream() {
            private val buf = ByteArrayOutputStream()
            private val single = ByteArray(1)

            @Synchronized
            override fun write(b: Int) {
                single[0] = b.toByte()
                write(single, 0, 1)
            }

            @Synchronized
            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                val room = limit - buf.size()
                if (room <= 0 || len <= 0) return
                buf.write(b, off, minOf(len, room))
            }

            @Synchronized
            fun text(): String = buf.toString(Charsets.UTF_8.name())
        }

        /**
         * 一次心跳确认会话写路径是否可用。
         * 注意: 静默黑洞 (中间设备静默丢包) 下本地 socket 写恒成功, 这里仍返回 true ——
         * 那类死亡由**往返探活** ([verifySessionRoundTrip], exec 通道 channel-open 必须收到服务端
         * 确认才能返回) 发现。**不要**改用 JSch 原生 `setServerAliveInterval`: 它做的是
         * 单向 keepalive 消息, 黑洞下同样永远"成功", 且历史上被误设成 30ms 间隔,
         * 高频小包 20s 后被中间设备 RST。
         */
        private fun isSessionWritable(pooled: PooledSession): Boolean =
            try {
                pooled.session.sendKeepAliveMsg()
                true
            } catch (e: Exception) {
                Log.w(TAG, "session heartbeat probe failed: ${e.message}")
                false
            }

        /**
         * 把除 [except] 外的全部会话标记 unhealthy 并并行触发重建。
         * 用于会话层失效 (channel is not opened / session is down) — 静默黑洞是路径级的,
         * 逐个会话独立重建会让新连接在重建窗口内反复撞上黑洞会话。
         */
        private fun markWholePoolUnhealthy(except: PooledSession) {
            var affected = 0
            for (other in pool) {
                if (other === except || !other.healthy) continue
                other.healthy = false
                if (!other.reconnecting) {
                    val idx = pool.indexOf(other).coerceAtLeast(0)
                    other.scope.launch { tryReconnectSession(other, idx) }
                }
                affected++
            }
            if (affected > 0) {
                VpnController.appLogThrottled(
                    "SSH 会话池整池重建 · 会话层失效 ($affected 个会话)",
                    level = LogLevel.WARNING,
                    throttleKey = "SSH 整池重建",
                )
            }
        }

        /**
         * 尝试重连单个 session（不影响其他 session）。
         * 防重入: [PooledSession.reconnecting] CAS — keepAlive 周期与 createDirectChannel
         * 失败路径可能同时请求重建同一 session, 两个循环并发 pool.remove/add 会互相踩。
         */
        private suspend fun tryReconnectSession(
            pooled: PooledSession,
            index: Int,
        ) {
            synchronized(pooled) {
                if (pooled.reconnecting) return
                pooled.reconnecting = true
            }
            try {
                reconnectSessionUnlocked(pooled, index)
            } finally {
                synchronized(pooled) {
                    pooled.reconnecting = false
                }
            }
        }

        private suspend fun reconnectSessionUnlocked(
            pooled: PooledSession,
            index: Int,
        ) {
            val config = currentConfig ?: return
            var retryCount = 0
            val maxRetries = 5

            while (retryCount < maxRetries && isConnectedFlag.get()) {
                retryCount++
                val backoffMs = minOf(1000L * retryCount, 10000L)
                Log.d(
                    TAG,
                    "Session pool-$index: reconnect attempt $retryCount/$maxRetries (backoff ${backoffMs}ms)",
                )
                delay(backoffMs)

                if (isConnectedFlag.get()) {
                    try {
                        pooled.session.disconnect()
                    } catch (_: Exception) {
                    }
                }

                try {
                    val newPooled = createSession(config, index)
                    if (newPooled == null) {
                        continue
                    }

                    // 原子替换: 取出旧的, 放入新的
                    pool.remove(pooled)
                    pool.add(newPooled)

                    statReconnectOk.incrementAndGet()
                    Log.d(TAG, "Session pool-$index: reconnected successfully")
                    // 取消旧 session 的 keepAlive 及其 scope (避免 CoroutineScope 泄漏)
                    pooled.keepAliveJob?.cancel()
                    pooled.scope.cancel()
                    startSessionKeepAlive(newPooled, config.keepAliveInterval.toLong(), index)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Session pool-$index: reconnect failed: ${e.message}")
                    VpnController.appLogThrottled(
                        "SSH 会话重连失败 · pool-$index — ${e.message}",
                        level = LogLevel.ERROR,
                        throttleKey = "SSH 会话重连失败",
                    )
                }
            }

            Log.e(TAG, "Session pool-$index: all reconnect attempts exhausted")
            statReconnectFail.incrementAndGet()
            VpnController.appLog(
                "SSH 会话 pool-$index 重连失败 · 已耗尽所有重试",
                LogLevel.ERROR,
            )
            // 如果所有 session 都挂了，通知断开
            checkPoolHealth()
        }

        /** internal 供单测验证「池失败置 false 后 disconnect 仍须排空」的泄漏场景 (判定逻辑不变)。 */
        internal fun checkPoolHealth() {
            if (pool.isEmpty() || pool.all { !it.healthy && !it.session.isConnected }) {
                Log.e(TAG, "All sessions unhealthy, triggering disconnect")
                VpnController.appLog(
                    "SSH 会话全部丢失 · 连接即将断开并触发重连",
                    level = LogLevel.ERROR,
                )
                isConnectedFlag.set(false)
                connectionState.value = ConnectionState.Failed
            }
        }

        override suspend fun disconnect(): Boolean {
            isConnectedFlag.set(false)

            connectionState.value = ConnectionState.Disconnecting
            drainPool()

            currentConfig = null
            connectionState.value = ConnectionState.Disconnected
            return true
        }

        /**
         * 无条件排空会话池: 取消每条会话的 keepAlive 协程、断开 Session、取消其 scope。
         *
         * 必须是无条件可调用的独立函数 —— `checkPoolHealth()` 会在**不排空池**的情况下把
         * `isConnectedFlag` 置 false (池失败 → Failed), 若 `disconnect()` 仍以该 flag 早退,
         * 池失败重连时排空循环永不执行, 每轮重连滞留最多 3 条 Session/Scope (§8 LEAK-1)。
         */
        private fun drainPool() {
            var drained = 0
            while (pool.isNotEmpty()) {
                val pooled = pool.poll() ?: break
                pooled.keepAliveJob?.cancel()
                try {
                    pooled.session.disconnect()
                } catch (_: Exception) {
                }
                pooled.scope.cancel()
                drained++
            }
            if (drained > 0) {
                Log.i(TAG, "Session pool drained: $drained session(s)")
            }
        }

        /** 仅供单测: 往池里种一条假会话, 用于锁死「无条件排空」语义。 */
        internal fun seedPooledSessionForTest(pooled: PooledSession) {
            pool.add(pooled)
        }

        /** 仅供单测: 当前池内会话数。 */
        internal fun poolSizeForTest(): Int = pool.size

        fun isConnected(): Boolean = isConnectedFlag.get() && pool.any { it.session.isConnected }

        /**
         * 连接池中是否有 session 已被标记为不健康 (断线已被 keepAlive/重连检测到)。
         * 供外部 (解锁后自动重连) 判断是否需要恢复, 比 isConnected() 更敏感:
         * isConnected() 只对『全部 session 都断开』才返回 false, 而死 socket 可能仍 isConnected=true。
         */
        fun hasUnhealthySession(): Boolean = pool.any { !it.healthy }

        /**
         * SSH 会话池资源快照 (周期日志用): 连接数 / 健康数 / 在途通道总数。
         *
         * 在途通道总数(= 各会话 `activeChannels` 之和)**持续不下降** = 通道泄漏 (某条关闭路径漏了
         * `activeChannels.decrementAndGet`), 早晚把会话读线程拖死 → 新连接全拿 0x05。
         * 这个数在旧实现里完全不可见 (只能靠"连接数对不上"猜)。
         */
        fun poolDiagnostics(): String {
            val now = pool.toList()
            val connected = now.count { it.session.isConnected }
            val healthy = now.count { it.healthy }
            val channels = now.sumOf { it.activeChannels.get() }
            val suspect = now.count { it.suspect }
            return "SSH 会话 $connected/${now.size} (健康 $healthy, 可疑 $suspect, 在途通道 $channels)"
        }

        /**
         * 创建直连通道 - 从 session 池中轮询选择健康的 session
         */
        override fun createDirectChannel(
            host: String,
            port: Int,
        ): TunnelChannel? {
            if (pool.isEmpty()) {
                Log.w(TAG, "createDirectChannel: session pool is empty")
                VpnController.appLogThrottled("SSH 会话池为空 · 无法打开通道", level = LogLevel.WARNING)
                return null
            }

            // 候选列表: 轮询起点保证公平, 按活跃 channel 数排序; **逐个尝试** —
            // 单个会话可能正处在断开瞬间 (JSch connected 标志是异步翻转的),
            // "channel is not opened"/"session is down" 换下一个健康会话即可透明吸收,
            // 不再像旧实现那样单次失败就把错误抛给客户端 (rep=5 → 下载失败)
            val snapshot = pool.toList()
            val size = snapshot.size
            if (size == 0) return null
            val start = sessionIndex.getAndIncrement()
            val now = System.currentTimeMillis()
            val candidates =
                (0 until size)
                    .map { snapshot[Math.floorMod(start + it, size)] }
                    .filter { it.session.isConnected && it.healthy }
                    // 刚开通道超时的会话排到最后: 它的 activeChannels 因失败反而更低,
                    // 只按活跃数排序等于"越坏越优先", 会在它身上反复烧掉整个预算。
                    .sortedWith(
                        compareBy(
                            { ChannelOpenBudget.isRecentlyStalled(it.lastOpenStallAtMs, now) },
                            { it.activeChannels.get() },
                        ),
                    )

            if (candidates.isEmpty()) {
                Log.w(TAG, "createDirectChannel: all sessions failed for $host:$port")
                val poolState =
                    pool.joinToString(prefix = "[", postfix = "]") {
                        "conn=${it.session.isConnected}/healthy=${it.healthy}"
                    }
                VpnController.appLogThrottled(
                    "SSH 通道打开失败 · 无可用会话 ($host:$port) (pool: $poolState)",
                    level = LogLevel.ERROR,
                    throttleKey = "SSH 通道打开失败",
                )
                // 池里一个可用会话都没有 (全断 / 全被判 unhealthy) 时必须主动体检:
                // keepAlive=0 没有周期协程去发现 session.isConnected==false, 只返回 null 会让
                // 新连接一直拿 0x05 而状态仍显示"已连接"。checkPoolHealth 的条件是
                // `all { !healthy && !isConnected }` (严格), 池里还有活口时不会误触发重建。
                checkPoolHealth()
                return null
            }

            var lastError: Exception? = null
            var attempted = 0
            var lastAttemptMs = 0L
            val connectStartedAt = System.currentTimeMillis()
            val deadlineAtMs = connectStartedAt + CHANNEL_BUDGET_MS
            for (pooled in candidates) {
                // 单候选超时 = min(候选份额, 剩余总预算, 硬上限): 份额保证首个候选吃满就换人,
                // 总预算保证所有候选加起来不超 CHANNEL_BUDGET_MS (代理 10s 关连接前必须收尾)。
                val attemptMs =
                    ChannelOpenBudget.attemptTimeoutMs(
                        deadlineAtMs = deadlineAtMs,
                        nowMs = System.currentTimeMillis(),
                        shareMs = CHANNEL_BUDGET_PER_CANDIDATE_MS,
                        maxMs = CHANNEL_CONNECT_TIMEOUT_MS.toLong(),
                    )
                if (attemptMs <= 0) break
                attempted++
                val candidateStartedAt = System.currentTimeMillis()
                try {
                    val channel =
                        pooled.session.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
                    channel.setHost(host)
                    channel.setPort(port)
                    channel.setInputStream(null)
                    channel.setOutputStream(null)
                    setChannelWindowSize(channel, CHANNEL_WINDOW_SIZE)
                    val input = channel.getInputStream()
                    val output = channel.getOutputStream()
                    pooled.activeChannels.incrementAndGet()
                    try {
                        channel.connect(attemptMs.toInt())
                    } catch (e: Exception) {
                        pooled.activeChannels.decrementAndGet()
                        throw e
                    }
                    statChannelOk.incrementAndGet()
                    // 通道成功 = 往返证据: 清除往返心跳的 suspect 标记 + 撤销 stall 后置
                    pooled.suspect = false
                    pooled.lastOpenStallAtMs = 0L
                    maybeLogChannelStats(host)
                    // 会话标识: 「已写未读」排障时用来区分会话级冻结 vs 单主机不应答
                    val sessionTag = "S" + Integer.toHexString(System.identityHashCode(pooled.session))
                    return JschTunnelChannel(channel, input, output, sessionTag) {
                        pooled.activeChannels.decrementAndGet()
                    }
                } catch (e: Exception) {
                    val elapsedMs = System.currentTimeMillis() - candidateStartedAt
                    lastAttemptMs = elapsedMs
                    // 拖到秒级 = 等不到 OPEN_CONFIRMATION (服务器还没把目标连上/会话读线程被堵),
                    // 与"服务器秒回 OPEN_FAILURE (目标不可达)"是两回事: 后者是目标问题, 不该怪会话。
                    // 这个区分只能靠耗时 —— JSch 对两种情况抛的是**同一条** "channel is not opened."
                    if (elapsedMs >= OPEN_STALL_MIN_MS) pooled.lastOpenStallAtMs = System.currentTimeMillis()
                    Log.w(TAG, "createDirectChannel failed: $host:$port", e)
                    lastError = e
                    // 区分「SSH 会话坏了」与「这一个 channel 打不开」:
                    // direct-tcpip 的 channel-open-failure 绝大多数来自**远端连不上目标**(目标层),
                    // 不是 session 死亡 —— 旧实现把两者都当 session 死亡并触发整池重建, 于是形成
                    // 「重建 → 重建窗口内新连接全失败 → 再重建」的雪崩, 下载反而全挂。
                    // 只在确认会话真的死了 (isConnected=false / session is down / 心跳写失败) 时才重建。
                    //
                    // `suspect` 只作为**静默黑洞**的旁证: 它只在往返探活撑到超时时才置位
                    // (服务器禁用 exec 时探活返回 ROUND_TRIP_UNAVAILABLE, 不置位) —— 否则
                    // exec 受限的服务器上 suspect 常驻, 一次正常的目标层失败就会凑成 sessionDead。
                    val sessionDead =
                        !pooled.session.isConnected ||
                            e.message?.contains("session is down") == true ||
                            pooled.suspect ||
                            !isSessionWritable(pooled)
                    if (sessionDead) {
                        statChannelSessionFail.incrementAndGet()
                        pooled.healthy = false
                        if (!pooled.reconnecting) {
                            val index = pool.indexOf(pooled).coerceAtLeast(0)
                            pooled.scope.launch {
                                tryReconnectSession(pooled, index)
                            }
                        }
                        // 会话层失效通常是路径级问题 (静默黑洞): 一次把其余会话也标记并并行重建,
                        // 压缩整体不可用窗口
                        markWholePoolUnhealthy(pooled)
                    } else {
                        statChannelTargetFail.incrementAndGet()
                    }
                    Log.d(
                        TAG,
                        "channel open failed: $host:$port elapsed=${elapsedMs}ms " +
                            "sessionDead=$sessionDead msg=${e.message}",
                    )
                    // 否则: 只跳过这个候选, 继续试下一个 (可能是目标临时不可达/超并发)
                }
            }

            // 所有候选均失败: 附 pool 快照 (暴露"选择时 isConnected 与 connect 时状态脱节"的模式)。
            // 「末次耗时」是 timeout (≈候选份额) 与「服务器秒回 OPEN_FAILURE」(RTT 级) 的唯一区分手段
            // —— JSch 两种情况的 message 完全一样, 不带上这个数就只能猜。
            val poolState =
                pool.joinToString(prefix = "[", postfix = "]") {
                    "conn=${it.session.isConnected}/healthy=${it.healthy}"
                }
            VpnController.appLogThrottled(
                "SSH 通道打开失败 · $host:$port — ${lastError?.message} · 试了 $attempted/${candidates.size} 个候选" +
                    " · 末次耗时 ${lastAttemptMs}ms · 在途通道 ${pool.sumOf { it.activeChannels.get() }}" +
                    " (pool: $poolState)",
                level = LogLevel.ERROR,
                throttleKey = "SSH 通道打开失败",
            )
            return null
        }

        /**
         * 反射设置通道窗口大小与发送包上限 (JSch 对应方法为包内可见)。
         * Method 引用只解析一次并缓存, 避免每连接遍历 declaredMethods。
         */
        private fun setChannelWindowSize(
            channel: com.jcraft.jsch.Channel,
            windowSize: Int,
        ) {
            try {
                CHANNEL_METHODS.forEach { m ->
                    if (m.name == "setLocalPacketSize") {
                        m.invoke(channel, CHANNEL_SEND_MAX_PACKET_SIZE)
                    } else {
                        m.invoke(channel, windowSize)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setChannelWindowSize failed: ${e.message}")
                VpnController.appLogThrottled(
                    "SSH 通道窗口设置失败 — ${e.message}",
                    level = LogLevel.WARNING,
                    throttleKey = "SSH 通道窗口设置失败",
                )
            }
        }

        private fun handleError(message: String) {
            isConnectedFlag.set(false)
            connectionState.value = ConnectionState.Failed
            Log.w(TAG, "handleError: $message")

            drainPool()
        }

        /**
         * 单次远程命令执行（用于服务器端配置助手）。
         *
         * 独立 Session，不占用 VPN 会话池；执行完立即断开。
         * 与 createSession 相同的安全算法白名单 + hostKey TOFU 校验。
         *
         * @param host 目标主机
         * @param port SSH 端口
         * @param username 登录账户
         * @param password 可选密码（仅内存，不落日志）
         * @param keyAlias 可选密钥别名（密码/密钥二选一）
         * @param stdinData 可选 stdin 数据（脚本/公钥/密码，避免 shell 参数拼接）
         * @param command 要执行的命令（由调用方构造，脚本内容固定）
         * @param timeoutMs 命令超时
         */
        override suspend fun execSingleShot(
            target: SshConnectionTarget,
            stdinData: ByteArray?,
            command: String,
            timeoutMs: Int,
        ): ExecResult {
            val config =
                ServerConfig(
                    name = "provision",
                    host = target.host,
                    port = target.port,
                    username = target.username,
                    keyAlias = target.keyAlias ?: "",
                    password = target.password,
                    connectTimeout = 15000,
                )
            return withContext(Dispatchers.IO) {
                var session: Session? = null
                var channel: ChannelExec? = null
                try {
                    val jsch = JSch()
                    if (!target.keyAlias.isNullOrEmpty()) {
                        keyManager.createJSchIdentity(jsch, target.keyAlias)
                    }
                    val s = jsch.getSession(target.username, target.host, target.port)
                    // 尽早赋值：connect/后续任何一步抛异常都由 finally 兜底 disconnect（修 Session 泄漏）
                    session = s
                    if (!target.password.isNullOrEmpty()) {
                        val passwordBytes = target.password.toByteArray(Charsets.UTF_8)
                        try {
                            s.setPassword(passwordBytes)
                        } finally {
                            java.util.Arrays.fill(passwordBytes, 0)
                        }
                    }
                    s.setHostKeyRepository(
                        KnownHostsHostKeyRepository(
                            knownHostsManager,
                            config.host,
                            config.port,
                            config.hostKeyFingerprint,
                        ) { fp ->
                            persistFingerprint(config, fp)
                        },
                    )
                    s.setConfig("StrictHostKeyChecking", "yes")
                    s.setConfig("PreferredAuthentications", "publickey,password")
                    s.setConfig("PubkeyAuthentication", "yes")
                    s.setConfig("PasswordAuthentication", "yes")
                    s.setConfig("max_input_buffer_size", MAX_INPUT_BUFFER_SIZE)
                    applyAlgorithmWhitelist(s)
                    s.setTimeout(config.connectTimeout)

                    s.connect()

                    val exec = s.openChannel("exec") as ChannelExec
                    channel = exec
                    exec.setCommand(command)
                    if (stdinData != null) {
                        exec.setInputStream(ByteArrayInputStream(stdinData))
                    } else {
                        exec.setInputStream(java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    exec.setErrStream(ByteArrayOutputStream())
                    exec.connect(timeoutMs)

                    val stdout = ByteArrayOutputStream()
                    val stderr = ByteArrayOutputStream()
                    val outStream = exec.getInputStream()
                    val buf = ByteArray(8192)
                    val deadline = System.currentTimeMillis() + timeoutMs
                    var timedOut = false
                    var interrupted = false
                    while (!exec.isClosed && !timedOut && !interrupted) {
                        if (System.currentTimeMillis() > deadline) {
                            timedOut = true
                            break
                        }
                        if (outStream.available() > 0) {
                            val n = outStream.read(buf)
                            if (n > 0) stdout.write(buf, 0, n)
                        }
                        val errData = (exec.errStream as? ByteArrayOutputStream)?.toByteArray()
                        if (errData != null && errData.isNotEmpty()) {
                            stderr.write(errData)
                            (exec.errStream as ByteArrayOutputStream).reset()
                        }
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    // 通道关闭前可能残留未读数据
                    if (exec.isClosed && outStream.available() > 0) {
                        val n = outStream.read(buf)
                        if (n > 0) stdout.write(buf, 0, n)
                    }
                    if (timedOut) {
                        exec.disconnect()
                        return@withContext ExecResult(-1, stdout.toString(Charsets.UTF_8.name()), "timeout")
                    }
                    val exitCode = exec.exitStatus
                    ExecResult(exitCode, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()))
                } catch (e: Exception) {
                    Log.w(TAG, "execSingleShot failed: ${e.message}")
                    ExecResult(-1, "", e.message ?: "exec failed")
                } finally {
                    try {
                        channel?.disconnect()
                    } catch (_: Exception) {
                    }
                    try {
                        session?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
