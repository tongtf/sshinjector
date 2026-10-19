package cn.srv0.sshinjector.data.remote.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChannelOpenBudget] 的不变量 (回归点见 2026-10-18 / 2026-10-19 真机日志):
 *
 * ① 单候选不能吃光总预算 —— 否则"逐个尝试候选"退化成"只试第一个", 一个会话开通道超时
 *    就等于整次建连失败 (日志: `SSH 通道打开失败 … channel is not opened.` + 3s 后 `rep=5`,
 *    而同一时刻别的连接只要 60~100ms, 说明池里还有好会话)。
 * ② 开通道迟滞过的会话必须被**后置** —— 它的 `activeChannels` 因失败反而更低, 只按活跃数
 *    排序会"越坏越优先"。
 * ③ 全部候选"秒拒"必须能与"超时"区分 (JSch 两种情况抛同一条 `channel is not opened.`,
 *    只能按耗时) —— 秒拒是"目标域名在服务器上解析不出来"(假 IP 模式下的 NXDOMAIN), 不是故障。
 */
class ChannelOpenBudgetTest {
    /** 生产口径: 总预算 9000ms ÷ 3 条会话 = 3000ms 份额, 单候选硬上限 10s。 */
    private val share = 3_000L
    private val max = 10_000L
    private val start = 1_000_000L
    private val deadline = start + 9_000L

    @Test
    fun `each candidate is capped by its share, not by the whole budget`() {
        assertEquals(share, ChannelOpenBudget.attemptTimeoutMs(deadline, start, share, max))
    }

    @Test
    fun `a stalled first candidate still leaves budget for the next two`() {
        // 第 1 个候选吃满 3s 后, 第 2 / 第 3 个候选必须还有可用份额 —— 这正是修掉的回归点:
        // 旧写法把 9000/3 当成**总**预算 (deadline = start + 3000), 3s 后 budgetLeft <= 0 直接
        // break, 后两个健康会话从没被尝试过。
        val afterFirst = start + share
        assertEquals(share, ChannelOpenBudget.attemptTimeoutMs(deadline, afterFirst, share, max))

        val afterSecond = start + 2 * share
        assertEquals(share, ChannelOpenBudget.attemptTimeoutMs(deadline, afterSecond, share, max))

        // 三个份额之和 == 总预算: 第 4 次尝试已无预算 → 调用方必须 break
        assertTrue(ChannelOpenBudget.attemptTimeoutMs(deadline, start + 3 * share, share, max) <= 0)
    }

    @Test
    fun `remaining budget wins over the share near the deadline`() {
        // 只剩 500ms 时必须缩到 500ms, 否则一次 connect 就打穿总预算 (代理 10s 关连接)
        assertEquals(500L, ChannelOpenBudget.attemptTimeoutMs(deadline, deadline - 500L, share, max))
    }

    @Test
    fun `hard cap wins when the share is larger than it`() {
        assertEquals(2_000L, ChannelOpenBudget.attemptTimeoutMs(deadline, start, shareMs = 5_000L, maxMs = 2_000L))
    }

    @Test
    fun `session that never stalled is not demoted`() {
        assertFalse(ChannelOpenBudget.isRecentlyStalled(lastStallAtMs = 0L, nowMs = start))
    }

    @Test
    fun `stalled session is demoted only inside the window`() {
        val now = 5_000_000L
        assertTrue(ChannelOpenBudget.isRecentlyStalled(now - 1L, now))
        assertTrue(ChannelOpenBudget.isRecentlyStalled(now - (ChannelOpenBudget.STALL_DEMOTE_MS - 1), now))
        assertFalse(ChannelOpenBudget.isRecentlyStalled(now - ChannelOpenBudget.STALL_DEMOTE_MS, now))
        assertFalse(ChannelOpenBudget.isRecentlyStalled(now - ChannelOpenBudget.STALL_DEMOTE_MS - 1, now))
    }

    @Test
    fun `all candidates rejected fast classifies as fast reject`() {
        // 真机形态: 假 IP 模式下一个不存在的域名 (update.kiwibrowser.com.unused) → sshd 解析不到
        // → 三个候选全在 124ms 被 CHANNEL_OPEN_FAILURE 秒拒。这是"目标不存在", 按 INFO 记, 不算故障。
        assertTrue(
            ChannelOpenBudget.isFastRejectOnAllCandidates(
                attempted = 3,
                candidateCount = 3,
                stalledAny = false,
            ),
        )
    }

    @Test
    fun `a candidate that reached the stall threshold disqualifies fast reject`() {
        // 拖到迟滞阈值 = 等不到 OPEN_CONFIRMATION (会话读线程被堵/服务器没连上目标) → 必须仍是 ERROR
        assertFalse(
            ChannelOpenBudget.isFastRejectOnAllCandidates(
                attempted = 3,
                candidateCount = 3,
                stalledAny = true,
            ),
        )
    }

    @Test
    fun `budget break before trying every candidate is not a fast reject`() {
        // 只试了 1/3 就 break (总预算耗尽) 说明那次是慢失败, 不能归成"远端不解析"
        assertFalse(
            ChannelOpenBudget.isFastRejectOnAllCandidates(
                attempted = 1,
                candidateCount = 3,
                stalledAny = false,
            ),
        )
        // 没有候选 (池被清空) 同样不成立
        assertFalse(
            ChannelOpenBudget.isFastRejectOnAllCandidates(
                attempted = 0,
                candidateCount = 0,
                stalledAny = false,
            ),
        )
    }
}
