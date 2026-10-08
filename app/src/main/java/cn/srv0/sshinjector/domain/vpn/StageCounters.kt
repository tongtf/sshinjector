package cn.srv0.sshinjector.domain.vpn

import java.util.concurrent.atomic.AtomicLong

/**
 * 分阶段健康计数 (进程级单例): TUN 写回 / DNS 应答投递 / 本地 SOCKS 转发三段的成败计数。
 * 热路径只做原子自增; [StageHealthEvaluator] 按探测窗口 (15s) 取增量做归因。
 * 计数全局单调, 新会话开始时由 VpnController.connect 调 [reset] 清零。
 */
object StageCounters {
    private val tunWritesOk = AtomicLong(0)
    private val tunWritesFailed = AtomicLong(0)
    private val dnsDelivered = AtomicLong(0)
    private val dnsErrors = AtomicLong(0)
    private val forwardAttempts = AtomicLong(0)
    private val forwardEstablished = AtomicLong(0)

    /** TUN 输出流一次写入的结果 (true=写成功 / false=写异常)。 */
    fun onTunWrite(success: Boolean) {
        if (success) {
            tunWritesOk.incrementAndGet()
        } else {
            tunWritesFailed.incrementAndGet()
        }
    }

    /** 一条 DNS 应答已交付给 TUN 投递循环 (含 SERVFAIL 等错误应答)。 */
    fun onDnsDelivered() {
        dnsDelivered.incrementAndGet()
    }

    /** 一次 DNS 解析以错误应答收场 (sendErrorResponse, 如上游超时/SERVFAIL)。 */
    fun onDnsError() {
        dnsErrors.incrementAndGet()
    }

    /** 一条 TCP 连接进入隧道转发路径 (forwardThroughLocalSocks)。 */
    fun onForwardAttempt() {
        forwardAttempts.incrementAndGet()
    }

    /** 隧道转发完成 SOCKS5 握手 + CONNECT, 连接已建立。 */
    fun onForwardEstablished() {
        forwardEstablished.incrementAndGet()
    }

    fun snapshot(
        packetLoopActive: Boolean,
        dnsQueries: Long,
    ): StageSignals =
        StageSignals(
            packetLoopActive = packetLoopActive,
            dnsQueries = dnsQueries,
            tunWritesOk = tunWritesOk.get(),
            tunWritesFailed = tunWritesFailed.get(),
            dnsDelivered = dnsDelivered.get(),
            dnsErrors = dnsErrors.get(),
            forwardAttempts = forwardAttempts.get(),
            forwardEstablished = forwardEstablished.get(),
        )

    fun reset() {
        tunWritesOk.set(0)
        tunWritesFailed.set(0)
        dnsDelivered.set(0)
        dnsErrors.set(0)
        forwardAttempts.set(0)
        forwardEstablished.set(0)
    }
}
