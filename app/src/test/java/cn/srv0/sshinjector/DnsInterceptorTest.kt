package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import cn.srv0.sshinjector.domain.vpn.VpnNetwork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DnsInterceptorTest {
    /**
     * 2026-10-19 真机: 白名单模式 (dnsMode=2) 下 `activeNetwork` 就是本 VPN, 它的 DNS 恒为
     * [VpnNetwork.TUN_IP] (`addDnsServer(10.0.0.2)`); 拿它当上游时, 受保护 socket 会把查询发到
     * **物理链路**上的 10.0.0.2 —— 无人应答, 现象是统计页「解析真实 IP」永远"解析不到"。
     * 这里钉住: 上游只剩 TUN IP 时**直接返回空且不发起任何 socket 查询** (`protectSocket` 设为
     * `fail` 来证明"一个查询都没发")。
     */
    @Test
    fun `resolveLocally never queries the vpn tun ip as upstream`() {
        val dns = DnsInterceptor()
        dns.setProtectFunction {
            fail("上游只有 TUN IP 时不得发起任何查询")
            false
        }
        dns.setSystemDnsServers(listOf(VpnNetwork.TUN_IP))
        assertTrue(dns.resolveLocally("example.com", timeoutMs = 50).isEmpty())
    }

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

    /**
     * 池耗尽必须**自愈**: 计数器推到上限后, 新域名的分配要触发全量重建 (清空两张映射表 +
     * 计数复位) 并重试一次, 而不是像旧实现那样永久返回 false
     * (REMOTE 模式此后所有 A 记录都解析不出来, 且不会恢复)。
     *
     * 同时锁死"回拨只发生在全量重建": 重建是整体清空, 不是按值回收再复用。
     */
    @Test
    fun `exhaustionTriggersSelfHealingReset`() {
        val dns = DnsInterceptor()
        val base = (198 shl 24) or (18 shl 16) // 198.18.0.0 (Int 为负数 —— 与生产一致)
        val poolMax = (198 shl 24) or (19 shl 16) or 0xFFFF // 198.19.255.255
        // 干净起点, 保证下面走的是"未命中 → 分配"而不是快路径复用
        dns.resetAllFakeIpsForTest()
        dns.exhaustFakeIpCounterForTest()
        assertEquals("seeded counter must sit at the pool max", poolMax, dns.fakeIpCounterForTest())

        val wire = buildQuery("heal.example", Type.A)
        assertTrue(
            "allocation after exhaustion must self-heal instead of failing permanently",
            dns.processDnsQuery(ByteBuffer.wrap(wire), clientIp, gatewayIp, 40010, 53),
        )
        // 自愈 = 计数复位到 base 后再分配一次 → base+1 (旧实现会停在 MAX 且返回 false)
        assertEquals("counter must be rewound to base+1 by the reset", base + 1, dns.fakeIpCounterForTest())
        assertEquals(
            "new domain must map to the first re-issued fake ip",
            "heal.example",
            dns.lookupDomain("198.18.0.1"),
        )
    }

    /**
     * N 个线程并发分配不同域名: 绝不能发出重复的假 IP。
     *
     * 重复 = 两个域名共用一张假 IP → 客户端 A 的 CONNECT 被解析成域名 B 的目标 (跨域名串号)。
     * 分配路径的"取号 → 写两张映射表"必须在同一把锁内完成。
     *
     * 线程全部 daemon + 有界 [CountDownLatch.await] (项目曾因无界等待挂死 CI 26 分钟)。
     */
    @Test
    fun `concurrentAllocDoesNotIssueDuplicateFakeIp`() {
        val dns = DnsInterceptor()
        dns.resetAllFakeIpsForTest()
        val threads = 32
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(threads) { i ->
            Thread {
                try {
                    start.await(30, TimeUnit.SECONDS)
                    val wire = buildQuery("c$i.example", Type.A)
                    if (!dns.processDnsQuery(ByteBuffer.wrap(wire), clientIp, gatewayIp, 50_000 + i, 53)) {
                        failures.add(IllegalStateException("allocation failed for c$i.example"))
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        start.countDown()
        assertTrue("concurrent allocations must finish (bounded wait)", done.await(30, TimeUnit.SECONDS))
        assertTrue("no allocation may fail: $failures", failures.isEmpty())

        // 干净起点 + N 次分配 → 假 IP 必落在 198.18.0.1..N, 且每张都映射到不同域名
        val domains = (1..threads).mapNotNull { dns.lookupDomain("198.18.0.$it") }
        assertEquals("every allocated fake ip must resolve to a domain", threads, domains.size)
        assertEquals("fake ips must not be shared across domains (no duplicates)", threads, domains.toSet().size)
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
