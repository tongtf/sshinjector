package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fake-IP 映射表的 LRU 语义: get 刷新访问时间, 超限优先淘汰最久未访问。
 * 之前是随机清半 — 客户端本地 DNS 缓存里的假 IP 失去映射后会黑洞重试 (下载卡"连接中")。
 */
class LruStringMapTest {
    @Test
    fun `put get roundtrip and miss returns null`() {
        val map = LruStringMap(16)
        map.put("198.18.0.1", "example.com")
        assertEquals("example.com", map.get("198.18.0.1"))
        assertNull(map.get("198.18.0.9"))
        assertEquals(1, map.size)
    }

    @Test
    fun `trim evicts least recently accessed first`() {
        val map = LruStringMap(10)
        repeat(12) { i ->
            map.put("k$i", "v$i")
            Thread.sleep(2) // lastAccessMs 毫秒粒度, 保证新旧有序
        }
        Thread.sleep(2)
        map.get("k0") // 刷新 k0 → 变为最新

        map.trim()

        // 12 > 10 → 压回 90% (9 条), 淘汰最老的 k1/k2/k3
        assertEquals(9, map.size)
        assertEquals("v0", map.get("k0"))
        assertNull(map.get("k1"))
        assertNull(map.get("k2"))
        assertNull(map.get("k3"))
        assertEquals("v4", map.get("k4"))
        assertEquals("v11", map.get("k11"))
    }

    @Test
    fun `trim is a no-op at or below capacity`() {
        val map = LruStringMap(4)
        repeat(4) { map.put("k$it", "v$it") }
        map.trim()
        assertEquals(4, map.size)
        repeat(4) { assertEquals("v$it", map.get("k$it")) }
    }

    @Test
    fun `put on existing key replaces value`() {
        val map = LruStringMap(8)
        map.put("k", "old")
        map.put("k", "new")
        assertEquals("new", map.get("k"))
        assertEquals(1, map.size)
    }

    @Test
    fun `values snapshot contains all values`() {
        val map = LruStringMap(8)
        map.put("a", "198.18.0.1")
        map.put("b", "198.18.0.2")
        val values = map.values()
        assertEquals(2, values.size)
        assertTrue(values.containsAll(listOf("198.18.0.1", "198.18.0.2")))
    }
}
