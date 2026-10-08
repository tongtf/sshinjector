package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.net.InetAddress
import java.nio.ByteBuffer

class DnsInterceptorTest {
    @Test
    fun `test DNS cache key generation`() {
        val name = "example.com"
        val type = 1 // A record
        val cacheKey = "$name.$type"
        assertEquals("example.com.1", cacheKey)
    }

    @Test
    fun `test DNS cache expiration`() {
        val ttl = 300 // 5 minutes in seconds
        val expireAt = System.currentTimeMillis() + ttl.toLong() * 1000
        assertTrue(expireAt > System.currentTimeMillis())
    }

    @Test
    fun `test DNS query ID counter`() {
        val counter =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val id1 = counter.incrementAndGet()
        val id2 = counter.incrementAndGet()
        assertNotEquals(id1, id2)
    }

    @Test
    fun `fake-ip write and read share the same cache key (F12-d)`() {
        runBlocking {
            val dns = DnsInterceptor()
            val wire = buildQuery("example.com", Type.A)

            assertTrue(dns.processDnsQuery(ByteBuffer.wrap(wire.clone()), clientIp, gatewayIp, 40000, 53))
            val afterFirst = dns.getStats()
            assertTrue("first query must be a cache miss", afterFirst.cacheMisses >= 1)
            assertEquals(0, afterFirst.cacheHits)

            // 第二次同名同类型查询: 写入键与读取键不一致时永远 miss
            assertTrue(dns.processDnsQuery(ByteBuffer.wrap(wire.clone()), clientIp, gatewayIp, 40001, 53))
            val afterSecond = dns.getStats()
            assertTrue("identical second query must hit the cache", afterSecond.cacheHits >= 1)
            assertEquals("same name+type must return the same fake ip", afterSecond.cacheSize, afterFirst.cacheSize)
        }
    }

    /**
     * 假 IP 计数器 clamp 必须按**无符号**比较: 198.18.0.0 的高位是 1, 作为 [Int] 是负数。
     *
     * 曾经 `resetFakeIpCounters` 用 `maxOf(cur.toLong(), maxSeen, FAKE_IP_BASE.toLong())` ——
     * 符号扩展成负 long, 而 `maxSeen` 是从字节拼出的正 long; `domainToIp` 里没有 IPv4 条目
     * (`maxSeen == 0`) 时 `maxOf(负, 0, 负)` 恒等于 **0** → 计数器被打回 0 →
     * 下一次分配拿到 0.0.0.1, `rawIp > FAKE_IP_MAX` 判为"池耗尽",
     * REMOTE 模式所有 A 记录解析失败且**不自愈** (分配失败 → 永远没有 IPv4 → 下轮再清零)。
     */
    @Test
    fun `resetFakeIpCounterNeverFallsBelowBase`() {
        val dns = DnsInterceptor()
        val base = (198 shl 24) or (18 shl 16) // 198.18.0.0 (Int 为负数 —— 这正是陷阱)
        val mid = base or 0x0012_3456
        val maxSeen = (198L shl 24) or (18L shl 16) or 0x1234

        // 剩余映射里没有 IPv4 条目 (maxSeen=0): 绝不能被打回 0
        assertEquals(base, dns.clampFakeIpCounter(base, 0))
        // 计数器已推进过 (高位为 1 → 负 Int) 必须原样保留
        assertEquals(mid, dns.clampFakeIpCounter(mid, 0))
        // 剩余映射的最大值优先 (重置到已用到的最大假 IP)
        assertEquals(maxSeen.toInt(), dns.clampFakeIpCounter(base, maxSeen))
        // 段外/未初始化 (旧 bug 形态 0、1) 必须被拉回基线
        assertEquals(base, dns.clampFakeIpCounter(0, 0))
        assertEquals(base, dns.clampFakeIpCounter(1, 0))
    }

    /**
     * 真实 IP 映射 (SYSTEM / DOMAIN_SPLIT 未命中路径的 DNS 回包) 必须进**独立的表**:
     * 单表时真实 IP 基数远超 16384, LRU 一淘汰就把假 IP 映射挤掉 → 客户端拿本地缓存的
     * 假 IP 发 CONNECT → 拿不到域名 → SOCKS 0x03 → 下载卡"连接中"。
     */
    @Test
    fun `real ip mappings must not evict fake ip mappings`() {
        val dns = DnsInterceptor()
        dns.seedFakeIpMappingForTest("198.18.0.2", "example.test")

        // 分表后 lookupDomain 仍要能反查真实 IP (连接日志靠它显示域名)
        var lastRealIp = ""
        // 压满真实 IP 表 (上限 8192): 30000 条远超容量, 必然触发淘汰
        repeat(30_000) { i ->
            lastRealIp = "10.${(i shr 16) and 0xFF}.${(i shr 8) and 0xFF}.${i and 0xFF}"
            dns.seedRealIpMappingForTest(lastRealIp, "h$i.example")
        }
        assertNotNull(dns.lookupDomain(lastRealIp))

        dns.trimMappingsForTest()

        assertNotNull(
            "fake IP mapping must survive a real-IP flood",
            dns.lookupDomain("198.18.0.2"),
        )

        // 假 IP 释放只动假 IP 表, 真实 IP 表不受影响
        dns.releaseFakeIp("198.18.0.2")
        // 归零后进入摘除宽限期, 宽限期内仍可解析 (客户端可能还持有这张假 IP, 立即删会 0x03)
        assertNotNull(dns.lookupDomain("198.18.0.2"))
        dns.sweepPendingFakeIpReleaseForTest(Long.MAX_VALUE)
        assertNull(dns.lookupDomain("198.18.0.2"))
        assertNotNull(dns.lookupDomain(lastRealIp))
    }

    @Test
    fun `AAAA query returns empty answer when ipv6 disabled (S5)`() {
        runBlocking {
            val dns = DnsInterceptor()
            dns.setEnableIPv6(false)
            val wire = buildQuery("v6.example.com", Type.AAAA)

            assertTrue(dns.processDnsQuery(ByteBuffer.wrap(wire), clientIp, gatewayIp, 40002, 53))
            // 应答必须已入队 (queriesResolved++, 非超时式丢弃), 且未分配/缓存 fd00 假 IP
            val stats = dns.getStats()
            assertEquals("empty AAAA answer must be enqueued", 1, stats.queriesResolved)
            assertEquals("no fake ipv6 must be allocated or cached", 0, stats.cacheSize)
        }
    }

    private val clientIp: InetAddress = InetAddress.getByName("10.0.0.2")
    private val gatewayIp: InetAddress = InetAddress.getByName("10.0.0.2")

    private fun buildQuery(
        qname: String,
        qtype: Int,
    ): ByteArray {
        val m = Message()
        m.header.id = 0x1234
        m.header.setFlag(Flags.RD.toInt())
        m.addRecord(Record.newRecord(Name.fromString("$qname."), qtype, DClass.IN, 0), Section.QUESTION)
        return m.toWire()
    }
}
