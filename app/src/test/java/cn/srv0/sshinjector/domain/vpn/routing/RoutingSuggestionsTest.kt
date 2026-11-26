package cn.srv0.sshinjector.domain.vpn.routing

import cn.srv0.sshinjector.domain.vpn.DomainStatSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由建议的过滤/成因/排序。
 *
 * 门槛是产品语义 (单次失败 = 抖动, 不该建议; 已有规则 = 不该重复建议),
 * 排序决定用户先看到哪 5 条 —— 都没法从 UI 上自查, 只能在这里钉死。
 */
class RoutingSuggestionsTest {
    private fun row(
        domain: String,
        failed: Long = 0,
        down: Long = 0,
        lastSeen: Long = 0,
        ipLiteral: Boolean = false,
    ) = DomainStatSnapshot(
        domain = domain,
        isIpLiteral = ipLiteral,
        connCount = 1,
        failedConnCount = failed,
        upBytes = 0,
        downBytes = down,
        firstSeenAt = 0,
        lastSeenAt = lastSeen,
        lastPort = 443,
        realIp = null,
        realIp2 = null,
    )

    private val noRules: (String) -> Boolean = { false }

    @Test
    fun `canRoute false yields nothing`() {
        val rows = listOf(row("example.com", failed = RoutingSuggestions.MIN_FAILED.toLong()))

        assertTrue(RoutingSuggestions.pick(rows, noRules, canRoute = false).isEmpty())
    }

    @Test
    fun `ip literals and already routed domains are skipped`() {
        val rows =
            listOf(
                row("example.com", failed = 3),
                row("1.2.3.4:443", failed = 3, ipLiteral = true),
                row("routed.com", failed = 3),
            )

        val picked = RoutingSuggestions.pick(rows, { it == "routed.com" }, canRoute = true)

        assertEquals(listOf("example.com"), picked.map { it.domain })
    }

    @Test
    fun `failed connections produce the failed reason`() {
        val rows = listOf(row("example.com", failed = RoutingSuggestions.MIN_FAILED.toLong(), lastSeen = 7))

        val picked = RoutingSuggestions.pick(rows, noRules, canRoute = true).single()

        assertEquals(RoutingSuggestionReason.FAILED, picked.reason)
        assertEquals(RoutingSuggestions.MIN_FAILED.toLong(), picked.failedConnCount)
        assertEquals(7L, picked.lastSeenAt)
    }

    @Test
    fun `heavy traffic produces the traffic reason`() {
        val rows = listOf(row("cdn.example.com", down = RoutingSuggestions.MIN_TRAFFIC_BYTES))

        val picked = RoutingSuggestions.pick(rows, noRules, canRoute = true).single()

        assertEquals(RoutingSuggestionReason.TRAFFIC, picked.reason)
        assertEquals(RoutingSuggestions.MIN_TRAFFIC_BYTES, picked.downBytes)
    }

    @Test
    fun `below both thresholds is not a suggestion`() {
        val rows =
            listOf(
                row("once.example.com", failed = 1, down = RoutingSuggestions.MIN_TRAFFIC_BYTES - 1),
                row("small.example.com", down = 1024),
            )

        assertTrue(RoutingSuggestions.pick(rows, noRules, canRoute = true).isEmpty())
    }

    @Test
    fun `sorts by failures then traffic then recency`() {
        val rows =
            listOf(
                row("a.com", failed = 3, down = 0, lastSeen = 1),
                row("b.com", failed = 3, down = 100, lastSeen = 2),
                row("c.com", failed = 3, down = 100, lastSeen = 5),
                row("d.com", failed = 5, down = 0, lastSeen = 1),
                row("e.com", down = RoutingSuggestions.MIN_TRAFFIC_BYTES, lastSeen = 99),
            )

        val picked = RoutingSuggestions.pick(rows, noRules, canRoute = true)

        assertEquals(listOf("d.com", "c.com", "b.com", "a.com", "e.com"), picked.map { it.domain })
    }

    @Test
    fun `limit caps the result`() {
        val rows = (1..8).map { row("d$it.example.com", failed = 10) }

        assertEquals(5, RoutingSuggestions.pick(rows, noRules, canRoute = true).size)
        assertEquals(2, RoutingSuggestions.pick(rows, noRules, canRoute = true, limit = 2).size)
        assertTrue(RoutingSuggestions.pick(rows, noRules, canRoute = true, limit = 0).isEmpty())
    }
}
