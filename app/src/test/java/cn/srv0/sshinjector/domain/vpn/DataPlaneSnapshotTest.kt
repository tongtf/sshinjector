package cn.srv0.sshinjector.domain.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DataPlaneSnapshot] 的文案与速率算术。
 *
 * 锁的是**方向语义** (AGENTS.md 记录过把"写 TUN"当上行打印, 把排障带偏):
 * `upBytes` = 读 TUN = 上行, `downBytes` = 写 TUN = 下行。
 * 同时锁住每个排障字段都还在 —— 这条日志是无 adb 现场唯一的观测面, 静默丢字段等于丢线索。
 */
class DataPlaneSnapshotTest {
    private fun snapshot(
        up: Long = 1000,
        down: Long = 2000,
        failure: String = "",
    ) = DataPlaneSnapshot(
        upBytes = up,
        downBytes = down,
        packetsReceived = 11,
        packetsSent = 22,
        rateText = "↑1 B/s ↓2 B/s",
        tunControlDropped = 3,
        tunControlDroppedBytes = 300,
        tunTrailingDropped = 7,
        tunNoInterfaceDropped = 5,
        resourceDiagnostics = "TUN 写队列 2 · 假 IP 映射 9/16384",
        tcpDiagnostics = "TCP 连接=1",
        udpDiagnostics = "UDP 丢弃 2 个",
        ipv6Diagnostics = "IPv6 NA=4",
        tunnelDiagnostics = "SA 通道数=2",
        failureSummary = failure,
    )

    @Test
    fun `up is the read side and down is the write side`() {
        val text = snapshot(up = 111, down = 999).render()
        assertTrue("上行必须来自读 TUN 计数: $text", text.contains("上行 111 B"))
        assertTrue("下行必须来自写 TUN 计数: $text", text.contains("下行 999 B"))
        // 反向拼接是最容易犯的错, 显式断言顺序
        assertTrue(text.contains("上行 111 B / 下行 999 B"))
    }

    @Test
    fun `every triage field survives rendering`() {
        val text = snapshot().render()
        assertTrue(text.startsWith("数据面快照 · "))
        assertTrue(text.contains("包 ↑11 ↓22"))
        assertTrue(text.contains("↑1 B/s ↓2 B/s"))
        assertTrue(text.contains("TUN 写丢弃 3 个(300B 控制包)"))
        assertTrue(text.contains("TUN 残留未解析 7B"))
        assertTrue(text.contains("TUN 接口空丢包 5"))
        assertTrue(text.contains("TUN 写队列 2"))
        assertTrue(text.contains("假 IP 映射 9/16384"))
        assertTrue(text.contains("TCP 连接=1"))
        assertTrue(text.contains("UDP 丢弃 2 个"))
        assertTrue(text.contains("IPv6 NA=4"))
        assertTrue(text.contains("SA 通道数=2"))
    }

    @Test
    fun `failure attribution section is omitted when the session had no failures`() {
        val text = snapshot(failure = "").render()
        assertTrue("无故障时不得出现空节或 0 次假数据: $text", !text.contains("故障归因"))
        assertTrue(!text.endsWith(" · "))
    }

    @Test
    fun `failure attribution section is appended when present`() {
        val text = snapshot(failure = "故障归因 · 本会话 1 类 12 次 — 上行背压丢段 ×12").render()
        assertTrue(text.endsWith(" · 故障归因 · 本会话 1 类 12 次 — 上行背压丢段 ×12"))
        // 归因必须在最后 (与它要解释的计数同屏, 且固定位置便于肉眼扫)
        assertTrue(text.indexOf("故障归因") > text.indexOf("TCP 连接=1"))
    }

    @Test
    fun `rate text is per second over the window`() {
        // 10s 窗口, 上行 +2000B / 下行 +5000B
        assertEquals("↑200 B/s ↓500 B/s", DataPlaneSnapshot.rateText(10, 3000, 6000, 1000, 1000))
    }

    @Test
    fun `rate text refuses to invent a rate without a baseline`() {
        // 首窗口 (windowSec <= 0) 不得拿累计值除以 0/1 编一个假速率
        assertEquals("速率待下一窗口", DataPlaneSnapshot.rateText(0, 3000, 6000, 0, 0))
        assertEquals("速率待下一窗口", DataPlaneSnapshot.rateText(-1, 3000, 6000, 0, 0))
    }

    @Test
    fun `rate text uses integer division so it never overstates throughput`() {
        // 3s 窗口 +1000B → 333 B/s (向下取整, 不能四舍五入放到 334)
        assertEquals("↑333 B/s ↓0 B/s", DataPlaneSnapshot.rateText(3, 1000, 0, 0, 0))
    }
}
