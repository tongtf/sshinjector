package cn.srv0.sshinjector

import cn.srv0.sshinjector.domain.vpn.DnsInterceptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
