package cn.srv0.sshinjector.domain.vpn

/**
 * 数据面快照 (60s 一条) 的**纯文本构建 + 速率算术**。
 *
 * 为什么单独抽出来: 无 adb 现场里这条日志是唯一的观测面, 文案本身就是接口 ——
 * - 方向语义必须钉死: `upBytes` = **读 TUN** = 上行 (应用 → 远端), `downBytes` = **写 TUN** = 下行
 *   (远端 → 应用)。历史上把"写 TUN"打印成"上行", 直接导致"上行是下行 12 倍"的误判,
 *   把排障带偏一整天 (见 `AGENTS.md` 方向语义那条)。
 * - 字段会随排障需要增删 (TUN 写丢弃 / 残留未解析 / 空丢包 / TCP / UDP / IPv6 / 隧道侧)。
 *   抽成纯函数后可以逐字锁进单测, 改动不会静默丢字段。
 *
 * 纯粹的字符串拼装, 不读任何全局状态; 计数器由调用方 ([cn.srv0.sshinjector.domain.usecase.VpnController])
 * 采样后传入。
 *
 * @param upBytes 读 TUN 累计字节 (上行)
 * @param downBytes 写 TUN 累计字节 (下行)
 * @param packetsReceived 读 TUN 累计包数
 * @param packetsSent 写 TUN 累计包数
 * @param rateText [rateText] 生成的本窗口速率文案
 * @param tunControlDropped 因写队列满被丢弃的**控制包**个数
 * @param tunControlDroppedBytes 同上的字节数
 * @param tunTrailingDropped 定界失败被丢弃的残留字节
 * @param tunNoInterfaceDropped 无 TUN 接口时丢弃的包数
 * @param tcpDiagnostics `PacketProcessor.tcpDiagnostics()`
 * @param udpDiagnostics `PacketProcessor.udpDiagnostics()`
 * @param ipv6Diagnostics `PacketProcessor.ipv6Diagnostics()`
 * @param tunnelDiagnostics 活跃隧道的 `tunnelDiagnostics()`
 */
internal data class DataPlaneSnapshot(
    val upBytes: Long,
    val downBytes: Long,
    val packetsReceived: Long,
    val packetsSent: Long,
    val rateText: String,
    val tunControlDropped: Long,
    val tunControlDroppedBytes: Long,
    val tunTrailingDropped: Long,
    val tunNoInterfaceDropped: Long,
    val tcpDiagnostics: String,
    val udpDiagnostics: String,
    val ipv6Diagnostics: String,
    val tunnelDiagnostics: String,
) {
    fun render(): String =
        "数据面快照 · 上行 $upBytes B / 下行 $downBytes B · " +
            "包 ↑$packetsReceived ↓$packetsSent · $rateText · " +
            "TUN 写丢弃 $tunControlDropped 个(${tunControlDroppedBytes}B 控制包) · " +
            "TUN 残留未解析 ${tunTrailingDropped}B · " +
            "TUN 接口空丢包 $tunNoInterfaceDropped · " +
            "$tcpDiagnostics · $udpDiagnostics · $ipv6Diagnostics · $tunnelDiagnostics"

    companion object {
        /**
         * 本窗口速率文案。
         *
         * @param windowSec 距上一窗口的秒数; `<= 0` 表示还没有可比基线 (首窗口),
         *   返回 "速率待下一窗口", 而不是拿累计值除以 0/1 编出假速率。
         * @param up / [down] 本窗口末的累计值
         * @param prevUp / [prevDown] 上一窗口末的累计值
         */
        fun rateText(
            windowSec: Long,
            up: Long,
            down: Long,
            prevUp: Long,
            prevDown: Long,
        ): String =
            if (windowSec > 0) {
                val upRate = (up - prevUp) / windowSec
                val downRate = (down - prevDown) / windowSec
                "↑$upRate B/s ↓$downRate B/s"
            } else {
                "速率待下一窗口"
            }
    }
}
