package cn.srv0.sshinjector.data.remote.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生成器 comment 必须是单 token, 否则会被 authorized_keys 校验判成多段。 */
class SshKeyManagerCommentTest {
    @Test
    fun `generated comment has no whitespace`() {
        val comment = generatedKeyComment(1_766_000_000_000L)
        assertTrue(comment.startsWith("android-generated@"))
        assertFalse(comment.any { it.isWhitespace() })
    }
}
