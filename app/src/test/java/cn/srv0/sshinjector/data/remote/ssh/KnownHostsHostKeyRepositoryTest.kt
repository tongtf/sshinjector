package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * KnownHostsHostKeyRepository 的 check() 返回值矩阵——JSch KEX 前置校验契约
 * (StrictHostKeyChecking=yes: OK 放行 / CHANGED 中止认证 / NOT_INCLUDED 中止认证)。
 */
class KnownHostsHostKeyRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var knownHosts: KnownHostsManager

    @Before
    fun setUp() {
        val context = Mockito.mock(Context::class.java)
        whenever(context.filesDir).thenReturn(tmp.root)
        knownHosts = KnownHostsManager(context)
    }

    /** 合法 host key blob：4 字节长度前缀 + "ssh-ed25519" + 载荷（HostKey.GUESS 读 key[8]/key[10]）。 */
    private fun blob(tag: Int): ByteArray {
        val type = "ssh-ed25519".toByteArray()
        val out = ByteArray(4 + type.size + 32)
        out[3] = type.size.toByte()
        type.copyInto(out, 4)
        out.fill(tag.toByte(), 4 + type.size)
        return out
    }

    /** 独立复现指纹公式：SHA256 over base64(blob) ASCII。 */
    private fun fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.getEncoder().encodeToString(key).toByteArray())
        return "SHA256:" + Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun repo(
        expectedFingerprint: String? = null,
        onFirstTrust: (String) -> Unit = {},
    ) = KnownHostsHostKeyRepository(knownHosts, "h.example", 2222, expectedFingerprint, onFirstTrust)

    @Test
    fun `check returns OK when configured fingerprint matches`() {
        val key = blob(1)
        assertEquals(HostKeyRepository.OK, repo(fingerprint(key)).check("h.example", key))
    }

    @Test
    fun `check returns CHANGED when configured fingerprint mismatches`() {
        assertEquals(HostKeyRepository.CHANGED, repo("SHA256:stale").check("h.example", blob(1)))
    }

    @Test
    fun `check accepts unknown host with TOFU and saves record`() {
        var trusted: String? = null
        val key = blob(1)
        val result = repo(onFirstTrust = { trusted = it }).check("h.example", key)

        assertEquals(HostKeyRepository.OK, result)
        assertEquals(fingerprint(key), trusted)
        assertTrue(File(tmp.root, "known_hosts").readText().contains("h.example,2222 "))
    }

    @Test
    fun `check returns OK for stored matching key without re-trusting`() {
        val key = blob(1)
        repo().check("h.example", key)

        var trusted: String? = null
        val result = repo(onFirstTrust = { trusted = it }).check("h.example", key)

        assertEquals(HostKeyRepository.OK, result)
        assertEquals(null, trusted)
    }

    @Test
    fun `check returns CHANGED when stored key differs`() {
        repo().check("h.example", blob(1))
        assertEquals(HostKeyRepository.CHANGED, repo().check("h.example", blob(2)))
    }

    @Test
    fun `check binds host-port so bracketed chost from jsch still matches`() {
        repo().check("[h.example]:2222", blob(1))
        assertEquals(HostKeyRepository.OK, repo().check("h.example", blob(1)))
        assertEquals(HostKeyRepository.CHANGED, repo().check("[h.example]:2222", blob(2)))
    }

    @Test
    fun `getHostKey returns empty array instead of null before any record`() {
        // JSch 在 check==OK 后对返回值直接取 .length——null 会 NPE
        assertEquals(0, repo().getHostKey("[h.example]:2222", "ssh-ed25519").size)
    }

    @Test
    fun `add stores key readable through getHostKey`() {
        val key = HostKey("h.example", HostKey.GUESS, blob(1))
        repo().add(key, null)

        val keys = repo().getHostKey(null, "ssh-ed25519")
        assertEquals(1, keys.size)
        assertArrayEquals(blob(1), Base64.getDecoder().decode(keys[0].getKey()))
    }
}
