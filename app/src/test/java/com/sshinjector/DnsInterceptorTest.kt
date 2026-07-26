package com.sshinjector

import org.junit.Assert.*
import org.junit.Test

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
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val id1 = counter.incrementAndGet()
        val id2 = counter.incrementAndGet()
        assertNotEquals(id1, id2)
    }
}
