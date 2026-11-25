package cn.srv0.sshinjector.ui.screen.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存回写纪律: 落库是异步的, 期间用户可能继续输入。
 *
 * 老实现 `mutableState.value = current.copy(savedText = current.text)` 用的是**启动时捕获**的旧快照,
 * 会把用户刚敲入的文本整段退回。正确形态: `savedText` = 本次落库内容, `text` = 用户当前最新输入。
 */
class MultiExitRulesSaveTest {
    @Test
    fun `after save keeps the newest text and marks only the saved content as clean`() {
        // 启动保存时的快照
        val atStart = MultiExitRulesUiState(loading = false, text = "a.com -> hk", savedText = "old")
        // 保存过程中用户又敲了一行
        val typedDuringSave = atStart.copy(text = "a.com -> hk\nb.com -> jp")

        val merged = typedDuringSave.afterSave(atStart.text)

        assertEquals("最新输入不得被旧快照覆盖", "a.com -> hk\nb.com -> jp", merged.text)
        assertEquals("savedText 必须是本次真正落库的文本", "a.com -> hk", merged.savedText)
        assertTrue("落库之后仍有未保存改动 → 需要继续显示 dirty", merged.dirty)
        assertTrue(merged.canSave)
    }

    @Test
    fun `after save with no further typing becomes clean`() {
        val state = MultiExitRulesUiState(loading = false, text = "a.com -> hk", savedText = "")

        val merged = state.afterSave(state.text)

        assertEquals("a.com -> hk", merged.text)
        assertEquals("a.com -> hk", merged.savedText)
        assertFalse(merged.dirty)
        assertFalse("无改动时不允许再保存", merged.canSave)
    }
}
