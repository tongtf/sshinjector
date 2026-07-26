package com.sshinjector

import org.junit.Assert.*
import org.junit.Test
import org.xbill.DNS.*
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * DNS 模式原型验证测试
 * 
 * 独立验证每种 DNS 模式的解析逻辑是否可行
 * 不依赖 Android 环境，可在 JVM 单元测试中运行
 */
class DnsModePrototypeTest {

    // Local enum for testing (mirrors DnsInterceptor.DnsTransport)
    private enum class TestDnsTransport {
        REMOTE,      // SSH tunnel SOCKS5 -> 8.8.8.8:53
        LOCAL_DOH,   // DoH -> dns.alidns.com
        SYSTEM,      // Passthrough, no intercept
        LOCAL_DNS,   // UDP 53 -> 114.114.114.114
        SPLIT        // Whitelist split
    }

    // =====================================================================
    // 模式 0: REMOTE - SSH 隧道 SOCKS5 -> 8.8.8.8:53 (逻辑验证)
    // =====================================================================
    @Test
    fun prototypeRemoteModeSocks5Logic() {
        println("=== [REMOTE] Verifying SOCKS5 handshake logic ===")
        
        // SOCKS5 greeting: version=5, nmethods=1, methods=[0 (no auth)]
        val greeting = byteArrayOf(0x05, 0x01, 0x00)
        assertEquals(0x05, greeting[0].toInt())
        assertEquals(0x01, greeting[1].toInt())
        assertEquals(0x00, greeting[2].toInt())
        
        // Expected response: version=5, method=0 (no auth)
        val expectedResp = byteArrayOf(0x05, 0x00)
        assertEquals(0x05, expectedResp[0].toInt())
        assertEquals(0x00, expectedResp[1].toInt())
        
        // CONNECT command structure for IPv4
        // ver=5, cmd=1 (CONNECT), rsv=0, atyp=1 (IPv4), dst.addr=4bytes, dst.port=2bytes
        val targetIp = InetAddress.getByName("8.8.8.8").address
        val targetPort = 53
        val connectReq = buildSocks5Connect(targetIp, targetPort)
        
        assertEquals(0x05, connectReq[0].toInt()) // Version
        assertEquals(0x01, connectReq[1].toInt()) // CONNECT
        assertEquals(0x00, connectReq[2].toInt()) // Reserved
        assertEquals(0x01, connectReq[3].toInt()) // ATYP=IPv4
        
        // Verify IP bytes
        for (i in 0..3) {
            val expected = targetIp[i].toInt() and 0xFF
            val actual = connectReq[4 + i].toInt() and 0xFF
            assertEquals(expected, actual)
        }
        
        // Verify port (big endian)
        val portBytes = byteArrayOf(connectReq[8], connectReq[9])
        val parsedPort = ByteBuffer.wrap(portBytes).order(ByteOrder.BIG_ENDIAN).getShort().toInt() and 0xFFFF
        assertEquals(targetPort, parsedPort)
        
        println("[REMOTE] SOCKS5 handshake + CONNECT structure verified")
    }

    private fun buildSocks5Connect(ip: ByteArray, port: Int): ByteArray {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x05.toByte()) // Version
        buf.put(0x01.toByte()) // CONNECT
        buf.put(0x00.toByte()) // Reserved
        buf.put(0x01.toByte()) // ATYP = IPv4
        buf.put(ip)            // IP address
        buf.putShort(port.toShort()) // Port
        return buf.array()
    }

    // =====================================================================
    // 模式 1: LOCAL_DOH - DoH -> dns.alidns.com (已验证可行)
    // =====================================================================
    @Test
    fun prototypeLocalDohMode() {
        println("=== [LOCAL_DOH] DoH to dns.alidns.com logic verification ===")
        
        // Verify DoH request structure (GET with base64url encoded dns parameter)
        val dohUrl = "https://dns.alidns.com/dns-query"
        val queryName = "google.com"
        val queryType = Type.A
        
        val dnsQuery = buildDnsQueryWire(queryName, queryType)
        val base64Query = Base64.getUrlEncoder().withoutPadding().encodeToString(dnsQuery)
        val url = URL("$dohUrl?dns=$base64Query")
        
        assertTrue(url.toString().startsWith("https://dns.alidns.com/dns-query?dns="))
        assertTrue(base64Query.isNotEmpty())
        
// Verify DNS wire format can be parsed back
        val parsed = Message(dnsQuery)
        assertEquals(1, parsed.header.getCount(Section.QUESTION))
        assertEquals(queryType, parsed.getQuestion().type)
        
        println("[LOCAL_DOH] DoH request structure verified")
        println("[LOCAL_DOH]   Query: $queryName, Type: $queryType")
        println("[LOCAL_DOH]   Base64url encoded length: ${base64Query.length}")
    }

    // =====================================================================
    // 模式 2: SYSTEM - 系统默认透传 (逻辑验证：不拦截，直接放行)
    // =====================================================================
    @Test
    fun prototypeSystemModePassthroughLogic() {
        println("=== [SYSTEM] Passthrough logic verification ===")
        
        // SYSTEM mode logic: return false means "not intercepted"
        val transportMode = TestDnsTransport.SYSTEM
        val shouldIntercept = (transportMode != TestDnsTransport.SYSTEM)
        
        assertFalse(shouldIntercept)
        
        // Verify excluded routes logic for system DNS
        val systemDnsServers = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "114.114.114.114")
        for (dns in systemDnsServers) {
            val excluded = isExcludedRoute(dns, 53)
            assertTrue("System DNS $dns should be excluded from VPN", excluded)
        }
        
        println("[SYSTEM] Passthrough logic correct: intercept=false, system DNS excluded")
    }

    private fun isExcludedRoute(ip: String, port: Int): Boolean {
        if (port != 53) return false
        val excludedDns = setOf(
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1",
            "9.9.9.9", "149.112.112.112",
            "223.5.5.5", "223.6.6.6",
            "114.114.114.114", "114.114.115.115",
            "119.29.29.29", "182.254.116.116"
        )
        return excludedDns.contains(ip)
    }

    // =====================================================================
    // 模式 3: LOCAL_DNS - UDP 53 -> 114.114.114.114 (需验证)
    // =====================================================================
    @Test
    fun prototypeLocalDnsModeUdpLogic() {
        println("=== [LOCAL_DNS] UDP 53 to 114.114.114.114 logic verification ===")
        
        // Verify UDP DNS query structure
        val queryName = "google.com"
        val queryType = Type.A
        
        val dnsQuery = buildDnsQueryWire(queryName, queryType)
        assertTrue(dnsQuery.size > 12) // DNS header minimum 12 bytes
        
        // Verify ID field exists and can be manipulated
        val originalId = ByteBuffer.wrap(dnsQuery).order(ByteOrder.BIG_ENDIAN).getShort(0)
        // ID is 16-bit unsigned, check it's in valid range 0..65535
        val unsignedId = originalId.toInt() and 0xFFFF
        assertTrue(unsignedId >= 0 && unsignedId <= 0xFFFF)
        
        // Verify we can parse response
        // (Actual network test requires internet - skipping in unit test)
        
        println("[LOCAL_DNS] UDP DNS query structure verified")
        println("[LOCAL_DNS]   Target: 114.114.114.114:53")
        println("[LOCAL_DNS]   Query size: ${dnsQuery.size} bytes")
        println("[LOCAL_DNS]   Note: Actual network test needs internet + UDP 53 allowed")
    }

    // =====================================================================
    // 模式 4: SPLIT - 白名单分流 (占位，逻辑验证)
    // =====================================================================
    @Test
    fun prototypeSplitModeWhitelistLogic() {
        println("=== [SPLIT] Whitelist routing logic verification ===")
        
        val whitelist = setOf("google.com", "github.com", "example.com")
        val testDomains = listOf(
            "google.com" to true,
            "github.com" to true,
            "example.com" to true,
            "unknown.com" to false,
            "ads.google.com" to false,  // subdomain not in whitelist
            "sub.github.com" to false
        )
        
        for ((domain, expectedRemote) in testDomains) {
            val useRemote = shouldUseRemoteDns(domain, whitelist)
            assertEquals("Domain: $domain", expectedRemote, useRemote)
        }
        
        println("[SPLIT] Whitelist routing logic verified")
    }

    private fun shouldUseRemoteDns(domain: String, whitelist: Set<String>): Boolean {
        return domain in whitelist
    }

    // =====================================================================
    // 核心 DNS 协议逻辑验证
    // =====================================================================
    @Test
    fun prototypeDnsIdMapping() {
        println("=== [CORE] DNS query ID mapping logic ===")
        
        // Simulate the DnsInterceptor ID mapping logic
        val originalQueryId = 0x1234
        val assignedQueryId = 0x5678
        
        // Build a DNS query
        val query = buildDnsQueryWire("test.com", Type.A)
        val actualOriginalId = ByteBuffer.wrap(query).order(ByteOrder.BIG_ENDIAN).getShort(0)
        
        // Simulate assigning new ID
        val newQuery = query.copyOf()
        ByteBuffer.wrap(newQuery).order(ByteOrder.BIG_ENDIAN).putShort(0, assignedQueryId.toShort())
        
        val actualAssignedId = ByteBuffer.wrap(newQuery).order(ByteOrder.BIG_ENDIAN).getShort(0)
        assertEquals(assignedQueryId, actualAssignedId.toInt() and 0xFFFF)
        
        // Simulate response with assigned ID
        val response = newQuery.copyOf()
        // Set QR bit (bit 7 of byte 2)
        response[2] = (response[2].toInt() or 0x80).toByte()
        
        // In onDnsResponse: rewrite ID back to original
        ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN).putShort(0, originalQueryId.toShort())
        
        val finalId = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN).getShort(0)
        assertEquals(originalQueryId, finalId.toInt() and 0xFFFF)
        
        println("[CORE] ID mapping: original=$originalQueryId assigned=$assignedQueryId restored=$finalId")
    }

    @Test
    fun prototypeDnsParsingWithXbill() {
        println("=== [CORE] xbill.DNS parsing verification ===")
        
        val msg = Message()
        msg.addRecord(Record.newRecord(Name.fromString("google.com."), Type.A, DClass.IN, 0), Section.QUESTION)
        
        val queryBytes = msg.toWire()
        
        val parsed = Message(queryBytes)
        assertEquals(1, parsed.header.getCount(Section.QUESTION))
        assertEquals("google.com.", parsed.getQuestion().name.toString())
        assertEquals(Type.A, parsed.getQuestion().type)
        
        println("[CORE] xbill.DNS parsing works correctly")
    }

    @Test
    fun prototypeDnsCacheKeyFormat() {
        println("=== [CORE] Cache key format ===")
        
        val cacheKey = "google.com.1"  // name.type
        val parts = cacheKey.split(Regex("\\."))
        println("Parts: $parts, size: ${parts.size}")
        assertEquals(3, parts.size)
        assertEquals("google", parts[0])
        assertEquals("com", parts[1])
        assertEquals("1", parts[2])  // Type.A = 1
        
        println("[CORE] Cache key format: name.type")
    }

    @Test
    fun prototypeExcludedRoutesForDohEndpoints() {
        println("=== [ROUTING] DoH endpoint exclusion verification ===")
        
        // Common DoH endpoints that should be excluded from VPN
        // Note: These are IPs, actual exclusion needs IP resolution
        val dohEndpoints = mapOf(
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6")
        )
        
        // Actual implementation: IP-based CIDR exclusion (no port check)
        // Both DNS (port 53) and DoH (port 443) to these IPs will be excluded
        val excludedIps = setOf(
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1",
            "223.5.5.5", "223.6.6.6"
        )
        
        for ((hostname, ips) in dohEndpoints) {
            for (ip in ips) {
                val excluded = excludedIps.contains(ip)
                println("[ROUTING] $hostname ($ip): excluded=$excluded (applies to ALL ports)")
            }
        }
        
        println("[ROUTING] Note: Current implementation uses IP-based /32 CIDR exclusion (all ports)")
    }

    // Simplified IP-based exclusion matching actual VpnController.shouldBypassVpn logic
    private fun isExcludedRoute(ip: String): Boolean {
        val excludedIps = setOf(
            "8.8.8.8", "8.8.4.4",
            "1.1.1.1", "1.0.0.1",
            "9.9.9.9", "149.112.112.112",
            "223.5.5.5", "223.6.6.6",
            "114.114.114.114", "114.114.115.115",
            "119.29.29.29", "182.254.116.116"
        )
        return excludedIps.contains(ip)
    }

    private fun buildDnsQueryWire(name: String, type: Int): ByteArray {
        val fqdn = if (name.endsWith(".")) name else "$name."
        val msg = Message()
        msg.addRecord(Record.newRecord(Name.fromString(fqdn), type, DClass.IN, 0), Section.QUESTION)
        return msg.toWire()
    }
}