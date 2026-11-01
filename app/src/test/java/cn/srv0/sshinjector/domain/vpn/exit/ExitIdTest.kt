package cn.srv0.sshinjector.domain.vpn.exit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出口命名规则 (设计 §5.3 / 附录 A): 小写字母/数字/`_`/`-`, 1..32, `default` 保留。
 *
 * 这里的错误都是**静默**的: 名字不合法时规则引用会变成"未知出口"而被跳过,
 * 表现是"规则配了不生效", 现场很难归因 —— 所以边界必须逐条锁住。
 */
class ExitIdTest {
    @Test
    fun `normalizes case and surrounding whitespace`() {
        assertEquals("hk", ExitId.normalize("  HK "))
        assertEquals("jp-1", ExitId.normalize("JP-1"))
        assertEquals("a_b", ExitId.normalize("A_B"))
    }

    @Test
    fun `rejects reserved default as a user exit name`() {
        assertNull(ExitId.normalize("default"))
        assertNull(ExitId.normalize(" DEFAULT "))
        assertTrue(ExitId.isValidReference("default"))
    }

    @Test
    fun `rejects empty and over long names`() {
        assertNull(ExitId.normalize(""))
        assertNull(ExitId.normalize("   "))
        assertNull(ExitId.normalize("a".repeat(ExitId.MAX_LENGTH + 1)))
        assertEquals("a".repeat(ExitId.MAX_LENGTH), ExitId.normalize("a".repeat(ExitId.MAX_LENGTH)))
    }

    @Test
    fun `rejects illegal characters`() {
        assertNull(ExitId.normalize("hk 1"))
        assertNull(ExitId.normalize("hk.1"))
        assertNull(ExitId.normalize("香港"))
        assertNull(ExitId.normalize("hk/1"))
    }

    @Test
    fun `reference validation accepts default and valid names but not junk`() {
        assertTrue(ExitId.isValidReference("default"))
        assertTrue(ExitId.isValidReference("HK"))
        assertFalse(ExitId.isValidReference(""))
        assertFalse(ExitId.isValidReference("hk 1"))
    }
}
