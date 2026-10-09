package cn.srv0.sshinjector.domain.vpn

import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelConfig
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelManager
import cn.srv0.sshinjector.domain.vpn.tunnel.TunnelPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * P1 (回程 TCP 客户端窗口流控闸门) + P2 (SYN-ACK Window Scale 回显)。
 *
 * 症状背景: 旧实现 SYN-ACK 无选项 → 无 WS 协商 → 客户端窗口封顶 64KB,
 * 且 writeTcpPayloadToTun 无流控无重传 —— 超窗注入被本机内核丢弃后
 * 假 TCP 栈无法重造 → Play 下载卡死。这些用例锁死修复语义。
 *
 * 阻塞类用例的注入线程一律 daemon + 有界等待 (本仓库曾有无界循环挂死 CI 26min 的前科)。
 */
class TcpWindowGateTest {
    private val clientIp = InetAddress.getByName("10.0.0.2")
    private val serverIp = InetAddress.getByName("93.184.216.34")
    private val clientIp6 = InetAddress.getByName("fd00::2")
    private val serverIp6 = InetAddress.getByName("2606:4700:4700::1111")
    private val srcPort = 41000
    private val dstPort = 443

    @Test
    fun `zero window blocks return payload until window update arrives (P1)`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            sendSyn(sm, clientIp, serverIp)
            assertTrue("handshake must reach SYN-ACK", awaitUntil { tunPackets.any(::isSynAck) })
            assertTrue("tun callback must register", awaitUntil { plugin.tunCallback != null })
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))
            val isn = readIsn(tunPackets.first(::isSynAck), ipHeader = 20)

            // 客户端通告窗口 = 0: 回程必须停住 (否则 = 旧行为, 超窗注入丢包无重传)
            sendAckWindow(sm, clientIp, serverIp, ack = isn + 1, window = 0)

            val payload = ByteArray(500) { 1 }
            val (_, done) = injectAsync(plugin, payload)
            assertFalse(
                "sender must be gated while window is 0",
                done.await(400, TimeUnit.MILLISECONDS),
            )
            assertTrue(
                "no payload may reach TUN while window is 0",
                tunPackets.none(::hasTcpPayload),
            )

            // window update: 闸门放开, 数据一次性送达
            sendAckWindow(sm, clientIp, serverIp, ack = isn + 1, window = 10000)
            assertTrue("payload must be delivered after window update", done.await(5, TimeUnit.SECONDS))
            assertEquals(500, tcpPayloadSum(tunPackets, ipHeader = 20))
        } finally {
            server.close()
        }
    }

    @Test
    fun `window scale offered in SYN is echoed and scales advertised window (P2)`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            // 客户端 SYN: MSS + NOP + WScale 9 (典型大窗口客户端)
            sendSyn(sm, clientIp, serverIp, options = mssOptions() + byteArrayOf(0x01, 0x03, 0x03, 0x09))
            assertTrue("handshake must reach SYN-ACK", awaitUntil { tunPackets.any(::isSynAck) })

            val synAck = tunPackets.first(::isSynAck)
            assertEquals("SYN-ACK with WS must carry 28B TCP header", 48, synAck.size)
            assertEquals("dataOffset=7", 0x70, synAck[32].toInt() and 0xFF)
            assertEquals("flags = SYN+ACK", 0x12, synAck[33].toInt() and 0xFF)
            assertEquals("MSS option kind", 0x02, synAck[40].toInt() and 0xFF)
            assertEquals("MSS option len", 0x04, synAck[41].toInt() and 0xFF)
            assertEquals("MSS 1460 (big-endian)", 1460, be16(synAck, 42))
            assertEquals("WS option kind", 0x03, synAck[45].toInt() and 0xFF)
            assertEquals("WS echo must be offered-with-shift-0", 0x00, synAck[47].toInt() and 0xFF)

            assertTrue("tun callback must register", awaitUntil { plugin.tunCallback != null })
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))
            val isn = readIsn(synAck, ipHeader = 20)
            // 通告窗口原值 65535 << scale 9 ≈ 33.5MB: 无 WS 协商时容量只有 65535 → 100KB 必卡死
            sendAckWindow(sm, clientIp, serverIp, ack = isn + 1, window = 65535)

            val payload = ByteArray(100_000) { 7 }
            val (_, done) = injectAsync(plugin, payload)
            assertTrue(
                "100KB payload must pass under scaled window (64KB cap regression)",
                done.await(10, TimeUnit.SECONDS),
            )
            assertEquals(100_000, tcpPayloadSum(tunPackets, ipHeader = 20))
            assertTrue(
                "IPv4 data segments must fit MTU 1500",
                tunPackets.filter(::hasTcpPayload).all { it.size <= 1500 },
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `wscale from absurd SYN value is clamped so the gate cannot stall (B1)`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            sendSyn(sm, clientIp, serverIp, options = mssOptions() + byteArrayOf(0x01, 0x03, 0x03, 0xFF.toByte()))
            assertTrue("handshake must reach SYN-ACK", awaitUntil { tunPackets.any(::isSynAck) })
            assertTrue("tun callback must register", awaitUntil { plugin.tunCallback != null })
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))
            val isn = readIsn(tunPackets.first(::isSynAck), ipHeader = 20)
            sendAckWindow(sm, clientIp, serverIp, ack = isn + 1, window = 65535)

            // shift 255 未 clamp 时: Long.shl 对 255 取 6 位 → 65535L<<63 为负 → 闸门恒闭 → 卡死
            val payload = ByteArray(100_000) { 3 }
            val (_, done) = injectAsync(plugin, payload)
            assertTrue(
                "payload must pass with clamped wscale=14",
                done.await(10, TimeUnit.SECONDS),
            )
            assertEquals(100_000, tcpPayloadSum(tunPackets, ipHeader = 20))
        } finally {
            server.close()
        }
    }

    @Test
    fun `malformed SYN options terminate parsing without hanging packetLoop (D2)`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            // case 1: kind=9 长度 0 (len<2) —— 畸形, 必须有界退出并按无 WS 处理
            val start1 = System.currentTimeMillis()
            sendSyn(sm, clientIp, serverIp, options = mssOptions() + byteArrayOf(0x09, 0x00, 0x00, 0x00))
            assertTrue("packetLoop must not hang on zero-len option", System.currentTimeMillis() - start1 < 2000)
            assertTrue("SYN-ACK must still be emitted", awaitUntil { tunPackets.any(::isSynAck) })
            val synAck1 = tunPackets.first(::isSynAck)
            assertEquals("malformed SYN must fall back to headerless-WS SYN-ACK", 0x60, synAck1[32].toInt() and 0xFF)
            assertEquals(44, synAck1.size)

            // 收到的 RST → closeTcpConnection(notifyBrowser=false), 不回包, 但同步移除条目
            // (case 2 的 SYN 因此必须走 create 分支 → 第二个 SYN-ACK; 若条目残留则无新 SYN-ACK)
            sendRst(sm, clientIp, serverIp, ack = readIsn(synAck1, ipHeader = 20) + 1)

            // case 2: kind=9 长度 99 (越界) —— p+len > end, 同样必须有界退出
            val start2 = System.currentTimeMillis()
            sendSyn(sm, clientIp, serverIp, options = mssOptions() + byteArrayOf(0x09, 0x63, 0x01, 0x02))
            assertTrue("packetLoop must not hang on overlong option", System.currentTimeMillis() - start2 < 2000)
            assertTrue(
                "entry must be free and second SYN-ACK emitted",
                awaitUntil { tunPackets.count(::isSynAck) >= 2 },
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun `connection close unblocks a gated sender (B3)`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            sendSyn(sm, clientIp, serverIp)
            assertTrue("handshake must reach SYN-ACK", awaitUntil { tunPackets.any(::isSynAck) })
            assertTrue("tun callback must register", awaitUntil { plugin.tunCallback != null })
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))
            val isn = readIsn(tunPackets.first(::isSynAck), ipHeader = 20)
            sendAckWindow(sm, clientIp, serverIp, ack = isn + 1, window = 0)

            val payload = ByteArray(500) { 9 }
            val (injector, done) = injectAsync(plugin, payload)
            assertFalse("sender must be gated while window is 0", done.await(300, TimeUnit.MILLISECONDS))

            // 浏览器 RST → closeTcpConnection 必须 notifyAll 唤醒闸门, 否则回程线程永久挂起
            sendRst(sm, clientIp, serverIp, ack = isn + 1)
            injector.join(3000)
            assertFalse("gated sender must exit after close", injector.isAlive)
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertTrue("gated payload must not be written after close", tunPackets.none(::hasTcpPayload))
        } finally {
            server.close()
        }
    }

    @Test
    fun `IPv6 return segments fit MTU 1500 with 1440 payload cap`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            sendSyn(sm, clientIp6, serverIp6)
            assertTrue("handshake must reach IPv6 SYN-ACK", awaitUntil { tunPackets.any(::isSynAck) })
            val synAck = tunPackets.first(::isSynAck)
            assertEquals("IPv6 SYN-ACK = 40 + 24B TCP (no WS)", 64, synAck.size)
            assertEquals("IPv6 payload length = 24B TCP header", 24, be16(synAck, 4))
            assertTrue("tun callback must register", awaitUntil { plugin.tunCallback != null })
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))
            val isn = readIsn(synAck, ipHeader = 40)
            sendAckWindow(sm, clientIp6, serverIp6, ack = isn + 1, window = 65535)

            val payload = ByteArray(3000) { 5 }
            val (_, done) = injectAsync(plugin, payload)
            assertTrue("payload must be delivered", done.await(10, TimeUnit.SECONDS))
            assertEquals(3000, tcpPayloadSum(tunPackets, ipHeader = 40))
            val dataSegments = tunPackets.filter(::hasTcpPayload)
            assertTrue("IPv6 data segments must fit MTU 1500", dataSegments.all { it.size <= 1500 })
            assertEquals("1440 cap → 1440 + 1440 + 120", 1440, dataSegments.maxOf { it.size - 60 })
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 上行记账与 ACK 号一致性 (乱序回归)。
     *
     * 故障形态: 客户端上传数据时某段乱序到达 (Play 商店的大请求会触发), 旧实现按
     * "累计写入字节" 推进 forwardedBytes, 于是 expected seq 被推到缺口之后 —— 缺口段重传时
     * seq < expected 被 dup 分支当成"已转发"丢弃, 数据永远补不回去, 客户端无限重传
     * (现场日志: TUN 上行 5.6MB 而隧道只收到 166KB, 且各丢弃计数全为 0)。
     */
    @Test
    fun `gap segment after out-of-order data is still forwarded (reorder regression)`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)

        try {
            sendSyn(sm, clientIp, serverIp)
            assertTrue("握手必须到达 SYN-ACK", awaitUntil { tunPackets.any(::isSynAck) })
            assertTrue("tun 回调必须注册", awaitUntil { plugin.tunCallback != null })
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))

            val acked = 12345 + 1 // SYN 之后期望的首个 seq

            // 1) 按序首段 → 必须转发, ACK 覆盖它
            sendDataSegment(sm, acked, SEG)
            assertTrue("首段必须被转发", awaitUntil { server.received() >= SEG })
            assertEquals("首段 ACK 必须覆盖已转发字节", acked + SEG, latestAck(tunPackets))

            // 2) 乱序段 (跳过缺口) → 不推进 ACK 基准
            sendDataSegment(sm, acked + 2 * SEG, SEG)
            Thread.sleep(200)
            assertEquals(
                "乱序段不得推进 ACK 基准",
                acked + SEG,
                latestAck(tunPackets),
            )

            // 3) 缺口段重传 → 必须被按序转发, 而不是被 dup 分支误判丢弃
            val before = server.received()
            sendDataSegment(sm, acked + SEG, SEG)
            assertTrue(
                "缺口段必须被转发到隧道 (不得被当作 dup 丢弃)",
                awaitUntil { server.received() >= before + SEG },
            )

            // 4) 补齐后 ACK 必须覆盖两段
            val ackAfter = latestAck(tunPackets)
            assertTrue(
                "补齐缺口后 ACK 必须推进到 ${acked + 2 * SEG}, 实际 $ackAfter",
                ackAfter >= acked + 2 * SEG,
            )
        } finally {
            server.close()
        }
    }

    /** 发送一个带 payload 的 PSH|ACK 段。 */
    private fun sendDataSegment(
        sm: TcpStateMachine,
        seq: Int,
        len: Int,
    ) {
        val headerLen = 20
        val pkt =
            ByteBuffer
                .allocate(headerLen + len)
                .order(ByteOrder.BIG_ENDIAN)
                .apply {
                    putShort(srcPort.toShort())
                    putShort(dstPort.toShort())
                    putInt(seq)
                    putInt(1)
                    putShort(((headerLen / 4 shl 12) or 0x18).toShort())
                    putShort(65535.toShort())
                    putShort(0)
                    putShort(0)
                    repeat(len) { put(0x5A) }
                }.array()
        sm.processTcpPacket(
            ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN),
            clientIp,
            serverIp,
            0,
            pkt.size,
        )
    }

    /** 最近一个纯 ACK 包的 ack 号 (IP 头 20 字节 → TCP 头偏移 20+4=24…ack 在 +32/+33 相对 IP 头)。 */
    private fun latestAck(tunPackets: List<ByteArray>): Int =
        tunPackets
            .lastOrNull { p -> isPureAck(p) }
            ?.let { p ->
                val ipHeader = if (p[0].toInt() shr 4 == 6) 40 else 20
                readInt(p, ipHeader + 8) // TCP ack 字段 = 头偏移 +8
            } ?: -1

    private fun isPureAck(p: ByteArray): Boolean {
        if (p.size < 34) return false
        val ipHeader = if (p[0].toInt() shr 4 == 6) 40 else 20
        if (p.size < ipHeader + 20) return false
        return (p[ipHeader + 13].toInt() and 0xFF) == 0x10
    }

    private fun readInt(
        p: ByteArray,
        at: Int,
    ): Int =
        ((p[at].toInt() and 0xFF) shl 24) or
            ((p[at + 1].toInt() and 0xFF) shl 16) or
            ((p[at + 2].toInt() and 0xFF) shl 8) or
            (p[at + 3].toInt() and 0xFF)

    @Test
    fun `return payload arriving during flush is queued instead of overtaking older data`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            sendSyn(sm, clientIp, serverIp)
            assertTrue(awaitUntil { tunPackets.any(::isSynAck) })
            assertTrue(plugin.tunCallback != null)
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))
            val isn = readIsn(tunPackets.first(::isSynAck), ipHeader = 20)

            // 零窗口 → 之后的回程必须排队 (等 flush), 而不是各自直写
            sendAckWindow(sm, clientIp, serverIp, ack = isn + 1, window = 0)
            val older = ByteArray(400) { 1 }
            val (_, done) = injectAsync(plugin, older)
            assertFalse("gated sender must not finish at zero window", done.await(400, TimeUnit.MILLISECONDS))

            // 窗口放开: older 被送出
            sendAckWindow(sm, clientIp, serverIp, ack = isn + 1, window = 5000)
            assertTrue("queued payload must flush after window update", done.await(5, TimeUnit.SECONDS))

            // 顺序断言: TUN 上 payload 的字节序列必须单调对应注入顺序,
            // 不能出现"后到的数据先于先到的" (旧实现在锁外读 isNotEmpty 会乱序)
            val seq: List<Byte> = tunPackets.flatMap { p -> payloadBytes(p, ipHeader = 20).toList() }
            assertEquals("no payload may be lost or duplicated", older.size, seq.size)
            assertTrue(
                "return stream must stay in order",
                seq.all { it == 1.toByte() },
            )
        } finally {
            server.close()
        }
    }

    /**
     * 回程缓存 (pendingReturn) 溢出必须被计数与上报。
     *
     * 旧实现队列满时**静默丢** (无 else 分支), 表现为"各丢弃计数全 0 但数据真的丢了",
     * 客户端只能无限重传 —— 这是排障最大的盲点。本用例锁死: 溢出必须计入快照。
     *
     * 数据注入发生在 SYN-ACK 发出前 (synAckSeq < 0) → 必定走缓存路径; 缓存上限 512KB,
     * 所以灌 600KB 必然溢出。
     */
    @Test
    fun `pendingReturn overflow is counted and reported instead of silently dropped`() {
        val server = FakeSocksServer()
        // 不回 CONNECT 应答 → 握手永不完成 (synAckSeq < 0), 回程数据必然全部走缓存路径
        server.stallConnectReply = true
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val sm = newStateMachine(plugin, tunPackets)
        try {
            sendSyn(sm, clientIp, serverIp)
            assertTrue("callback must register", awaitUntil { plugin.tunCallback != null })
            val total = 600 * 1024
            val block = ByteArray(64 * 1024) { 7 }
            var sent = 0
            while (sent < total) {
                plugin.tunCallback?.invoke(block, 0, block.size)
                sent += block.size
            }
            val diag = sm.diagnostics()
            assertTrue(
                "overflow must appear in diagnostics, was: $diag",
                diag.contains("回程缓存溢出") && !diag.contains("回程缓存溢出 0B"),
            )
        } finally {
            server.close()
        }
    }

    /**
     * 同一假 IP 的并发连接: 只有最后一条关闭才释放 DNS 映射。
     *
     * 背景: 上一版实现在**单条** TCP 关闭时就删映射, 而 HTTP/2 对同一域名有多条并发 TCP,
     * 先关的那条会删掉仍在用的映射 → 后续 CONNECT 拿到 SOCKS 0x03 被拒 → 下载卡"连接中"。
     */
    @Test
    fun `fake ip mapping is released only after the last concurrent connection closes`() {
        val server = FakeSocksServer()
        val plugin = FakeSocksPlugin(server.port)
        val tunPackets = CopyOnWriteArrayList<ByteArray>()
        val dns = DnsInterceptor()
        val sm = newStateMachine(plugin, tunPackets, dns)
        // 假 IP 目标 (198.18.0.2 = DnsInterceptor 分配段), 与真实域名用例区分开
        val fakeIp = InetAddress.getByName("198.18.0.2")
        val ip = requireNotNull(fakeIp.hostAddress)
        dns.seedFakeIpMappingForTest(ip, "example.test")
        try {
            // 两条不同源端口 → 同一假 IP 的并发连接
            // 逐条发, 每条都要等自己的 SYN-ACK 出来再发下一条 (并发握手有先后, 避免抢同一 fake IP 的建立时序)
            sendSyn(sm, clientIp, fakeIp)
            assertTrue(awaitUntil { tunPackets.any(::isSynAck) })
            sendSyn(sm, InetAddress.getByName("10.0.0.3"), fakeIp, fromPort = 51000)
            assertTrue("both handshakes must reach SYN-ACK", awaitUntil { tunPackets.count(::isSynAck) >= 2 })
            assertTrue(server.awaitConnect(5, TimeUnit.SECONDS))
            assertEquals("both connections must be counted as active", 2, sm.fakeIpActiveConnCount(ip))
            assertNotNull("seeded mapping must be visible", dns.lookupDomain(ip))

            // 第三条连接只发 SYN 就关掉 (吞 CONNECT 应答 → 永远建立不了):
            // 它从未 acquire, close 时**不得**递减计数, 否则多端口并发场景下
            // 计数被提前扣光 → 映射在仍被使用时被删 → 后续 CONNECT 拿 0x03 卡"连接中"。
            server.stallConnectReply = true
            sendSyn(sm, InetAddress.getByName("10.0.0.4"), fakeIp, fromPort = 52000)
            sm.closeForTest(connSrcPort = 52000)
            assertEquals(
                "closing a connection that never acquired must not decrement",
                2,
                sm.fakeIpActiveConnCount(ip),
            )

            // 关掉其中一条: 映射必须仍在 (另一条还在用)
            sm.closeForTest(connSrcPort = srcPort)
            assertEquals(
                "closing one of two connections must NOT release the mapping",
                1,
                sm.fakeIpActiveConnCount(ip),
            )
            assertNotNull("mapping must survive while another connection uses it", dns.lookupDomain(ip))

            // 关掉最后一条: 计数归零。映射**不立即删** —— 客户端常不遵守 5s TTL, 现场实测会在
            // 归零后 ~6s 拿同一张假 IP 重试, 立即删会让这些重试全拿 SOCKS 0x03 秒拒
            // (日志: 假 IP 映射失效 → 下载卡"连接中")。先确认宽限期内仍可解析。
            sm.closeForTest(connSrcPort = 51000)
            assertEquals(
                "mapping must be released when the last connection closes",
                0,
                sm.fakeIpActiveConnCount(ip),
            )
            assertNotNull(
                "mapping must stay resolvable during the release grace period",
                dns.lookupDomain(ip),
            )

            // 宽限期满 (30s cleanupScheduler 扫一次) 才真正摘除: 映射必须真的删掉,
            // 不能只清 refcount —— 旧实现里 releaseFakeIp 因 counter==0 提前 return 成死代码,
            // 映射永远留在表里 (池 16384 打满)。
            dns.sweepPendingFakeIpReleaseForTest(Long.MAX_VALUE)
            assertNull(
                "the DNS mapping itself must be removed after the grace period, not just the refcount",
                dns.lookupDomain(ip),
            )
        } finally {
            server.close()
        }
    }

    private companion object {
        const val SEG = 1000
    }

    private fun newStateMachine(
        plugin: TunnelPlugin,
        tunPackets: CopyOnWriteArrayList<ByteArray>,
        dnsInterceptor: DnsInterceptor? = null,
    ): TcpStateMachine {
        val tunnelManager = mock<TunnelManager>()
        whenever(tunnelManager.getActiveOrFallback()).thenReturn(plugin)
        return TcpStateMachine(
            tunnelManager,
            { { p: ByteArray -> tunPackets.add(p) } },
            PacketStats(),
            SshIoDispatcher(),
        ).also { if (dnsInterceptor != null) it.setDnsInterceptor(dnsInterceptor) }
    }

    /** 在 daemon 线程里注入回程 payload (闸门可能阻塞, 绝不能跑在测试主线程)。 */
    private fun injectAsync(
        plugin: FakeSocksPlugin,
        payload: ByteArray,
    ): Pair<Thread, CountDownLatch> {
        val done = CountDownLatch(1)
        val t =
            Thread {
                try {
                    plugin.tunCallback?.invoke(payload, 0, payload.size)
                } finally {
                    done.countDown()
                }
            }
        t.isDaemon = true
        t.start()
        return t to done
    }

    private fun sendSyn(
        sm: TcpStateMachine,
        src: InetAddress,
        dst: InetAddress,
        options: ByteArray = ByteArray(0),
        fromPort: Int = srcPort,
    ) {
        val pkt = buildTcpPacket(0x02, 12345, 0, options = options, fromPort = fromPort)
        sm.processTcpPacket(ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN), src, dst, 0, pkt.size)
    }

    private fun sendAckWindow(
        sm: TcpStateMachine,
        src: InetAddress,
        dst: InetAddress,
        ack: Int,
        window: Int,
    ) {
        val pkt = buildTcpPacket(0x10, 12346, ack, window = window)
        sm.processTcpPacket(ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN), src, dst, 0, pkt.size)
    }

    private fun sendRst(
        sm: TcpStateMachine,
        src: InetAddress,
        dst: InetAddress,
        ack: Int,
    ) {
        val pkt = buildTcpPacket(0x14, 12346, ack)
        sm.processTcpPacket(ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN), src, dst, 0, pkt.size)
    }

    @Suppress("LongParameterList")
    private fun buildTcpPacket(
        flags: Int,
        seq: Int,
        ack: Int,
        window: Int = 65535,
        options: ByteArray = ByteArray(0),
        fromPort: Int = srcPort,
    ): ByteArray {
        val headerLen = 20 + options.size
        return ByteBuffer
            .allocate(headerLen)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putShort(fromPort.toShort())
                putShort(dstPort.toShort())
                putInt(seq)
                putInt(ack)
                putShort(((headerLen / 4 shl 12) or flags).toShort())
                putShort(window.toShort())
                putShort(0)
                putShort(0)
                put(options)
            }.array()
    }

    private fun mssOptions() = byteArrayOf(0x02, 0x04, 0x05, 0xB4.toByte())

    /** 取出某个数据段的 TCP payload 字节 (按 TCP 头长, 非固定 20)。 */
    private fun payloadBytes(
        p: ByteArray,
        ipHeader: Int,
    ): ByteArray {
        if (!hasTcpPayload(p)) return ByteArray(0)
        val tcpHeaderLen = ((p[ipHeader + 12].toInt() and 0xFF) shr 4) * 4
        val from = ipHeader + tcpHeaderLen
        return p.copyOfRange(from, p.size)
    }

    private fun be16(
        p: ByteArray,
        at: Int,
    ): Int = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)

    private fun readIsn(
        synAck: ByteArray,
        ipHeader: Int,
    ): Int {
        val off = ipHeader + 4
        return ByteBuffer.wrap(synAck, off, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun isSynAck(p: ByteArray): Boolean {
        if (p.size < 34) return false
        val ipHeader = if (p[0].toInt() shr 4 == 6) 40 else 20
        if (p.size < ipHeader + 14) return false
        return (p[ipHeader + 13].toInt() and 0xFF) == 0x12
    }

    /** 有 TCP payload 的数据段 (排除 SYN/RST/FIN 与纯 ACK)。IPv4/IPv6 布局: TCP 头在 ipHeader 偏移。 */
    private fun hasTcpPayload(p: ByteArray): Boolean {
        val ipHeader = if (p[0].toInt() shr 4 == 6) 40 else 20
        if (p.size <= ipHeader + 20) return false
        val flags = p[ipHeader + 13].toInt() and 0xFF
        return flags and 0x07 == 0
    }

    private fun tcpPayloadSum(
        packets: List<ByteArray>,
        ipHeader: Int,
    ): Int = packets.filter(::hasTcpPayload).sumOf { it.size - (ipHeader + 20) }

    private fun awaitUntil(
        timeoutMs: Long = 5000,
        cond: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    /** 最小可用 SOCKS5 服务端: 用户名/密码认证 + CONNECT, 支持多连接。 */
    private class FakeSocksServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private val connectLatch = CountDownLatch(1)
        private val receivedBytes = AtomicLong(0)

        /**
         * 吞掉 CONNECT 应答 (不回 success): 让被测连接永远停在握手未就绪态。
         * 回程缓存溢出用例靠它保证数据必然走缓存路径 (synAckSeq < 0), 不依赖时序运气。
         */
        @Volatile var stallConnectReply: Boolean = false

        /** 服务端实际收到的上游字节数 (SOCKS 解封装之后)。 */
        fun received(): Long = receivedBytes.get()

        fun awaitConnect(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = connectLatch.await(timeout, unit)

        init {
            Thread {
                try {
                    while (true) {
                        val s = serverSocket.accept()
                        // 每连接独立线程: handle() 末尾会一直读到 EOF, 串行处理会让第二条
                        // 并发连接的 SOCKS 握手永远排不上队 (连接就绪用例需要并发)。
                        Thread {
                            handle(s)
                        }.apply {
                            isDaemon = true
                            start()
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        private fun handle(s: Socket) {
            try {
                s.soTimeout = 5000
                val inp = s.getInputStream()
                val out = s.getOutputStream()

                // 方法选择: VER NM METHODS...
                inp.read()
                val nm = inp.read()
                repeat(nm) { inp.read() }
                out.write(byteArrayOf(0x05, 0x02))
                out.flush()

                // RFC 1929 认证
                inp.read()
                val ul = inp.read()
                repeat(ul) { inp.read() }
                val pl = inp.read()
                repeat(pl) { inp.read() }
                out.write(byteArrayOf(0x01, 0x00))
                out.flush()

                // CONNECT: VER CMD RSV ATYP ADDR PORT
                inp.read()
                inp.read()
                inp.read()
                when (inp.read()) {
                    0x01 -> repeat(6) { inp.read() }
                    0x04 -> repeat(18) { inp.read() }
                    0x03 -> {
                        val l = inp.read()
                        repeat(l + 2) { inp.read() }
                    }
                    else -> return
                }
                connectLatch.countDown()
                if (stallConnectReply) {
                    // 保持连接但不回应: 被测侧停在握手未就绪 (synAckSeq < 0), 直到对端关闭
                    while (inp.read() >= 0) {
                        // drain: 不回 CONNECT 应答
                    }
                    return
                }
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                // 消费浏览器→服务端数据并累计字节 (上行是否真正送达隧道的唯一证据)
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(chunk)
                    if (n <= 0) break
                    receivedBytes.addAndGet(n.toLong())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
        }

        override fun close() {
            try {
                serverSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    private class FakeSocksPlugin(
        override val localSocksPort: Int,
    ) : TunnelPlugin {
        @Volatile var tunCallback: ((ByteArray, Int, Int) -> Unit)? = null

        override val id = "fake-socks"
        override val socksAuth: Pair<String, String> = "user" to "pass"

        override suspend fun connect(config: TunnelConfig): Result<Unit> = Result.success(Unit)

        override suspend fun disconnect() = Unit

        override fun openTcpChannel(
            host: String,
            port: Int,
        ): TunnelChannel? = null

        override fun registerTunCallback(
            clientPort: Int,
            callback: (ByteArray, Int, Int) -> Unit,
        ) {
            tunCallback = callback
        }

        override fun removeTunCallback(clientPort: Int) {
            tunCallback = null
        }
    }
}
