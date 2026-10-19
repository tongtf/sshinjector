package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T-02: 数据面接线 —— `TcpStateMachine.closeTcpConnection` 必须把每条连接**恰好一次**记录到
 * 注入的 [DomainStatsStore]。
 *
 * 路线 (a): 复用 `TcpCloseBannerTest` 的最小假件姿势。把插件指向一个**已关闭**的端口,
 * `awaitLoopbackConnect` 秒被拒 → 连接立刻走到收尾分支 → `closeTcpConnection(...)`。
 * 全程无真网络、无轮询自旋, 确定性且 <5s。
 *
 * 等待用**有界** `CountDownLatch.await(5s)`: `recordClose` 在关闭包写出**之前**执行,
 * 所以"看到 RST"就意味着 store 已经写完 —— 不需要 sleep 去赌时序。
 */
class TcpDomainStatsTest {
    private val clientIp = InetAddress.getByName("10.0.0.2")
    private val serverIp = InetAddress.getByName("93.184.216.34")
    private val srcPort = 41000
    private val dstPort = 443

    @Test
    fun `a closed connection is recorded exactly once in the injected store`() {
        // 已关闭的端口 (use{} 退出即关闭监听): connect 立即被拒, 不依赖任何对端行为
        val closedPort = ServerSocket(0).use { it.localPort }
        val plugin = FakeSocksPlugin(closedPort)
        val store = DomainStatsStore()
        // recordClose 先于关闭包写出, 因此 RST 到达 TUN 时 store 必然已就绪。
        val rstLatch = CountDownLatch(1)
        val sm = newStateMachine(plugin, store) { p -> if (isRst(p)) rstLatch.countDown() }

        sendSyn(sm)

        assertTrue(
            "连接必须在 5s 内关闭并发出 RST (recordClose 先于关闭包, 故 store 此时已写完)",
            rstLatch.await(5, TimeUnit.SECONDS),
        )
        assertEquals("关闭后恰好一条域名记录", 1, store.size())
        val row = store.snapshot().single()
        assertEquals("这条连接只被记录一次", 1L, row.connCount)
        // dnsInterceptor 未注入 → 反查不到域名 → 按 IP:port 字面量记账
        assertEquals("无域名映射时以 IP:port 作键", "${serverIp.hostAddress}:$dstPort", row.domain)
        assertTrue("无域名映射时 isIpLiteral 必须为 true", row.isIpLiteral)
        assertEquals("本地 SOCKS 建连失败 = 内部失败, 计一次失败连接", 1L, row.failedConnCount)
        assertEquals("最近端口取本次连接的目的端口", dstPort, row.lastPort)
    }

    @Test
    fun `the resolved owner uid is attached to the recorded row`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val store = DomainStatsStore()
        val rstLatch = CountDownLatch(1)
        val resolver = FakeUidResolver(uid = 10086)
        val sm =
            newStateMachine(FakeSocksPlugin(closedPort), store, resolver) { p ->
                if (isRst(p)) rstLatch.countDown()
            }

        sendSyn(sm)

        assertTrue("连接必须在 5s 内关闭", rstLatch.await(5, TimeUnit.SECONDS))
        assertEquals("④ 反查到的 uid 必须回写到统计条目", 10086, store.snapshot().single().appUid1)
        assertEquals("查询用的必须是本连接的五元组 (客户端 srcPort)", srcPort, resolver.srcPort)
        assertEquals("查询用的必须是本连接的五元组 (目的端口)", dstPort, resolver.dstPort)
        assertTrue("源地址必须是客户端的原始字节", resolver.srcIp.contentEquals(clientIp.address))
        assertTrue("目的地址必须是目标的原始字节", resolver.dstIp.contentEquals(serverIp.address))
    }

    @Test
    fun `a negative uid from the resolver never lands in the row`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val store = DomainStatsStore()
        val rstLatch = CountDownLatch(1)
        // 平台返回 -1 = 无归属; 契约是"不回调", 这里故意回调以锁死 store 侧的哨兵守卫
        val resolver = FakeUidResolver(uid = DomainStatsStore.NO_UID)
        val sm =
            newStateMachine(FakeSocksPlugin(closedPort), store, resolver) { p ->
                if (isRst(p)) rstLatch.countDown()
            }

        sendSyn(sm)

        assertTrue("连接必须在 5s 内关闭", rstLatch.await(5, TimeUnit.SECONDS))
        assertEquals(
            "无归属不得写进任何 uid 槽",
            DomainStatsStore.NO_UID,
            store.snapshot().single().appUid1,
        )
    }

    @Test
    fun `a late uid callback arriving after close is still recorded`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val store = DomainStatsStore()
        val rstLatch = CountDownLatch(1)
        // 真实现是"单线程池里排队", 短命连接上回调往往在**关闭之后**才回来; 契约是回调里补写一次
        // (否则 0s 短连接永远拿不到归属 —— 真机命中率只有 7/105 的根因就是这种时序)。
        val resolver = DeferredUidResolver(uid = 10010)
        val sm =
            newStateMachine(FakeSocksPlugin(closedPort), store, resolver) { p ->
                if (isRst(p)) rstLatch.countDown()
            }

        sendSyn(sm)

        assertTrue("连接必须在 5s 内关闭", rstLatch.await(5, TimeUnit.SECONDS))
        assertEquals(
            "关闭那一刻还没拿到 uid, 条目先按无归属记账",
            DomainStatsStore.NO_UID,
            store.snapshot().single().appUid1,
        )

        resolver.complete()

        assertEquals(
            "晚到的回调必须补写 (短命连接全靠这条路径)",
            10010,
            store.snapshot().single().appUid1,
        )
    }

    private fun newStateMachine(
        plugin: TunnelPlugin,
        store: DomainStatsStore,
        appUidResolver: AppUidResolver = AppUidResolver.NONE,
        onTunPacket: (ByteArray) -> Unit,
    ): TcpStateMachine {
        val tunnelManager = mock<TunnelManager>()
        whenever(tunnelManager.getActiveOrFallback()).thenReturn(plugin)
        return TcpStateMachine(
            tunnelManager,
            { tunSinkOf(onTunPacket) },
            PacketStats(),
            SshIoDispatcher(),
            store,
            appUidResolver,
        )
    }

    private fun sendSyn(sm: TcpStateMachine) {
        val pkt = buildTcpPacket(0x02, 12345, 0)
        sm.processTcpPacket(
            ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN),
            IpPacketParser.ParsedPacket(clientIp, serverIp, 6, 0, pkt.size),
        )
    }

    private fun isRst(p: ByteArray) = p.size >= 34 && (p[33].toInt() and 0x04) != 0

    /** 纯 TCP 头 (无 IP 层), payloadStart=0; 与 TcpCloseBannerTest 的构造保持一致。 */
    private fun buildTcpPacket(
        flags: Int,
        seq: Int,
        ack: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(20)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(srcPort.toShort())
                putShort(dstPort.toShort())
                putInt(seq)
                putInt(ack)
                putShort(((5 shl 12) or flags).toShort())
                putShort(65535.toShort())
                putShort(0)
                putShort(0)
            }.array()

    /**
     * 假反查器: **同步**回调 (真实现是异步单线程池), 于是"uid 回写"变成确定性断言 —— 不需要
     * sleep 赌线程调度, 但也不覆盖真实现的线程/队列纪律 (那条由 `AppUidResolverTest` 负责)。
     */
    private class FakeUidResolver(
        private val uid: Int,
    ) : AppUidResolver {
        var srcIp: ByteArray? = null
        var srcPort: Int = -1
        var dstIp: ByteArray? = null
        var dstPort: Int = -1

        override fun lookup(
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
            onResolved: (Int) -> Unit,
        ) {
            this.srcIp = srcIp
            this.srcPort = srcPort
            this.dstIp = dstIp
            this.dstPort = dstPort
            onResolved(uid)
        }

        override fun diagnostics(): String = "fake"
    }

    /** 延迟回调替身: 模拟"单线程池排队 → 关闭之后才回来"的时序 (真实现的常见形态)。 */
    private class DeferredUidResolver(
        private val uid: Int,
    ) : AppUidResolver {
        private var callback: ((Int) -> Unit)? = null

        override fun lookup(
            srcIp: ByteArray,
            srcPort: Int,
            dstIp: ByteArray,
            dstPort: Int,
            onResolved: (Int) -> Unit,
        ) {
            callback = onResolved
        }

        fun complete() {
            callback?.invoke(uid)
        }

        override fun diagnostics(): String = "deferred"
    }

    /** 最小 SOCKS 插件: 只需要能返回一个指向已关闭端口的 localSocksPort。 */
    private class FakeSocksPlugin(
        override val localSocksPort: Int,
    ) : TunnelPlugin {
        override val id = "fake-socks"
        override val socksAuth: Pair<String, String> = "user" to "pass"

        override suspend fun connect(config: TunnelConfig): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit
    }
}
