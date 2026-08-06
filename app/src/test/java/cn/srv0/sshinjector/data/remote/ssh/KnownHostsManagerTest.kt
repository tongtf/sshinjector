package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import com.jcraft.jsch.HostKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.io.File

/**
 * KnownHostsManager 的 TOFU 记录增删逻辑测试。
 * 用 mock Context 指向临时目录，不依赖 Android 运行时。
 * 注意：android.util.Base64 在 JVM 单测中返回 null，因此指纹断言仅校验行前缀存在。
 */
class KnownHostsManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: KnownHostsManager

    @Before
    fun setUp() {
        val context = Mockito.mock(Context::class.java)
        whenever(context.filesDir).thenReturn(tmp.root)
        manager = KnownHostsManager(context)
    }

    private fun knownHostsFile(): File = File(tmp.root, "known_hosts")

    private fun writeKnownHosts(vararg lines: String) {
        knownHostsFile().writeText(lines.joinToString("\n"))
    }

    @Test
    fun `removeHostKey deletes only the matching host-port line`() {
        writeKnownHosts(
            "old.example,22 ecdsa-sha2-nistp256 AAAA SHA256:old",
            "other.example,22 ecdsa-sha2-nistp256 BBBB SHA256:other",
        )
        assertTrue(manager.removeHostKey("old.example", 22))
        val content = knownHostsFile().readText()
        assertTrue(content.contains("other.example,22"))
        assertFalse(content.contains("old.example,22"))
    }

    @Test
    fun `removeHostKey keeps other ports of the same host`() {
        writeKnownHosts(
            "host.example,22 ecdsa-sha2-nistp256 AAAA SHA256:a",
            "host.example,2222 ecdsa-sha2-nistp256 BBBB SHA256:b",
        )
        assertTrue(manager.removeHostKey("host.example", 22))
        val content = knownHostsFile().readText()
        assertTrue(content.contains("host.example,2222"))
        assertFalse(content.contains("host.example,22 "))
    }

    @Test
    fun `removeHostKey returns false when no matching record`() {
        writeKnownHosts("other.example,22 ecdsa-sha2-nistp256 AAAA SHA256:a")
        assertFalse(manager.removeHostKey("missing.example", 22))
    }

    @Test
    fun `new host key accepted again after removal (re-TOFU)`() {
        writeKnownHosts("host.example,22 ecdsa-sha2-nistp256 AAAA SHA256:old")
        manager.removeHostKey("host.example", 22)

        val freshKey = "ssh-ed25519 AAAAAC3NzaC1lZDI1NTE5AAAAIG2K1rQwC0kQhVtBzQyXb3b1b3b1b3b1b3b1b3b1b3b1b3b1b comment"
        val hostKey = Mockito.mock(HostKey::class.java)
        whenever(hostKey.getKey()).thenReturn(freshKey)
        whenever(hostKey.type).thenReturn("ssh-ed25519")

        // 无记录时 verifyHostKey 走 TOFU：保存并返回 true
        assertTrue(manager.verifyHostKey("host.example", 22, hostKey))
        assertTrue(knownHostsFile().readText().contains("host.example,22 "))
    }
}
