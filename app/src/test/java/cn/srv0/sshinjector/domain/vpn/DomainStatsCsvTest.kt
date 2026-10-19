package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [DomainStatsCsv] 的对外契约。
 *
 * 锁的是**导出格式** (列顺序 / 表头 / RFC4180 转义 / 文件名), 因为这四样是用户和外部脚本
 * 直接依赖的东西 —— 改动会让已导出的文件与旧版并存时无法对齐解析, 所以必须逐字钉住。
 */
class DomainStatsCsvTest {
    /** 固定列顺序的期望表头: 改这里就等于改导出契约, 需同步设计文档。 */
    private val expectedHeader =
        "domain,real_ip,real_ip2,is_ip_literal,conn_count,failed_conn_count," +
            "up_bytes,down_bytes,first_seen_ms,last_seen_ms,last_port,app_uids,app"

    private fun row(
        domain: String = "example.com",
        isIpLiteral: Boolean = false,
        realIp: String? = null,
        realIp2: String? = null,
        appUids: List<Int> = emptyList(),
    ) = DomainStatSnapshot(
        domain = domain,
        isIpLiteral = isIpLiteral,
        connCount = 2,
        failedConnCount = 1,
        upBytes = 100,
        downBytes = 200,
        firstSeenAt = 1000,
        lastSeenAt = 2000,
        lastPort = 443,
        realIp = realIp,
        realIp2 = realIp2,
        appUid1 = appUids.getOrElse(0) { -1 },
        appUid2 = appUids.getOrElse(1) { -1 },
        appUid3 = appUids.getOrElse(2) { -1 },
    )

    @Test
    fun `header matches the frozen contract exactly`() {
        val text = DomainStatsCsv.render(emptyList())
        assertEquals("表头必须逐字符一致", "$expectedHeader\n", text)
    }

    @Test
    fun `plain row keeps column order and renders null ips as empty fields`() {
        val text = DomainStatsCsv.render(listOf(row(domain = "example.com")))
        assertEquals(
            "$expectedHeader\nexample.com,,,false,2,1,100,200,1000,2000,443,,\n",
            text,
        )
    }

    @Test
    fun `row with real ips renders them in real_ip and real_ip2 columns`() {
        val text =
            DomainStatsCsv.render(
                listOf(row(domain = "example.com", isIpLiteral = true, realIp = "1.1.1.1", realIp2 = "2.2.2.2")),
            )
        assertEquals(
            "$expectedHeader\nexample.com,1.1.1.1,2.2.2.2,true,2,1,100,200,1000,2000,443,,\n",
            text,
        )
    }

    @Test
    fun `field containing comma is wrapped in quotes`() {
        val text = DomainStatsCsv.render(listOf(row(domain = "a,b.com")))
        assertEquals(
            "$expectedHeader\n\"a,b.com\",,,false,2,1,100,200,1000,2000,443,,\n",
            text,
        )
    }

    @Test
    fun `field containing quote doubles it and wraps the field`() {
        val text = DomainStatsCsv.render(listOf(row(domain = "he\"llo.com")))
        assertEquals(
            "$expectedHeader\n\"he\"\"llo.com\",,,false,2,1,100,200,1000,2000,443,,\n",
            text,
        )
    }

    @Test
    fun `field containing newline is wrapped in quotes`() {
        val text = DomainStatsCsv.render(listOf(row(domain = "a\nb.com")))
        assertEquals(
            "$expectedHeader\n\"a\nb.com\",,,false,2,1,100,200,1000,2000,443,,\n",
            text,
        )
    }

    @Test
    fun `a single app uid and its label render unquoted`() {
        val text =
            DomainStatsCsv.render(
                listOf(row(domain = "example.com", appUids = listOf(10500))),
                appLabels = mapOf(10500 to "Chrome"),
            )
        assertEquals(
            "$expectedHeader\nexample.com,,,false,2,1,100,200,1000,2000,443,10500,Chrome\n",
            text,
        )
    }

    @Test
    fun `multiple app uids are quoted and unknown uids fall back to uid N`() {
        // 与页面「应用」列同口径: 认不出名字时保留机器事实 (`uid N`), 不隐藏、不留空白。
        val text =
            DomainStatsCsv.render(
                listOf(row(domain = "example.com", appUids = listOf(10500, 10123, 1000))),
                appLabels = mapOf(10500 to "Chrome"),
            )
        assertEquals(
            "$expectedHeader\nexample.com,,,false,2,1,100,200,1000,2000,443," +
                "\"10500,10123,1000\",\"Chrome, uid 10123, uid 1000\"\n",
            text,
        )
    }

    @Test
    fun `an empty uid slot is filtered out of both app columns`() {
        // 空槽是 -1 (DomainStatsStore.NO_UID): 只留真实存在的槽, 不留空字段/重复分隔符
        val text =
            DomainStatsCsv.render(
                listOf(row(domain = "example.com", appUids = listOf(10500, -1, 1000))),
                appLabels = mapOf(10500 to "Chrome"),
            )
        assertEquals(
            "$expectedHeader\nexample.com,,,false,2,1,100,200,1000,2000,443," +
                "\"10500,1000\",\"Chrome, uid 1000\"\n",
            text,
        )
    }

    @Test
    fun `empty list yields only the header line`() {
        val text = DomainStatsCsv.render(emptyList())
        assertEquals(1, text.removeSuffix("\n").lines().size)
        assertTrue("空列表必须只有表头", text.endsWith("\n"))
        assertEquals(expectedHeader, text.removeSuffix("\n"))
    }

    @Test
    fun `fileName is deterministic for a fixed instant and matches the pattern`() {
        val nowMs = 1_700_000_000_000L
        val name = DomainStatsCsv.fileName(nowMs)
        assertTrue(
            "文件名必须匹配 sshinjector-domains-yyyyMMdd-HHmmss.csv: $name",
            Regex("""^sshinjector-domains-\d{8}-\d{6}\.csv$""").matches(name),
        )
        // 用同一 SimpleDateFormat + 默认时区复算, 不依赖当前时间 (固定 nowMs → 确定输出)。
        val expected =
            "sshinjector-domains-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs)) + ".csv"
        assertEquals(expected, name)
    }
}
