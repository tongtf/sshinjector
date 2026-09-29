package cn.srv0.sshinjector.data.remote.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7: 算法白名单键名与算法名双重校验。
 *
 * 键名错误 (KexAlgorithms/HostKeyAlgorithms/Cipher/MAC) 是静默 no-op——
 * Session 只读 kex/server_host_key/cipher.c2s/cipher.s2c/mac.c2s/mac.s2c/PubkeyAcceptedAlgorithms,
 * 所以必须断言白名单真的落进了 session config, 而不是只断言字符串拼对了。
 */
class JschAlgorithmWhitelistTest {
    private val session: Session = JSch().getSession("user", "127.0.0.1", 22)

    init {
        JschSshClient.applyAlgorithmWhitelist(session)
    }

    @Test
    fun `whitelist lands in session config under keys jsch actually reads`() {
        assertEqualsToWhitelist("kex", JschSshClient.KEX_ALGORITHMS)
        assertEqualsToWhitelist("server_host_key", JschSshClient.HOST_KEY_ALGORITHMS)
        assertEqualsToWhitelist("PubkeyAcceptedAlgorithms", JschSshClient.PUBKEY_ACCEPTED_ALGORITHMS)
        assertEqualsToWhitelist("cipher.c2s", JschSshClient.CIPHER_ALGORITHMS)
        assertEqualsToWhitelist("cipher.s2c", JschSshClient.CIPHER_ALGORITHMS)
        assertEqualsToWhitelist("mac.c2s", JschSshClient.MAC_ALGORITHMS)
        assertEqualsToWhitelist("mac.s2c", JschSshClient.MAC_ALGORITHMS)
    }

    @Test
    fun `every whitelisted algorithm name is known to JSch`() {
        assertNamesKnown("kex", JschSshClient.KEX_ALGORITHMS)
        assertNamesKnown("server_host_key", JschSshClient.HOST_KEY_ALGORITHMS)
        assertNamesKnown("PubkeyAcceptedAlgorithms", JschSshClient.PUBKEY_ACCEPTED_ALGORITHMS)
        assertNamesKnown("cipher.c2s", JschSshClient.CIPHER_ALGORITHMS)
        assertNamesKnown("mac.c2s", JschSshClient.MAC_ALGORITHMS)
    }

    private fun assertEqualsToWhitelist(
        key: String,
        expected: String,
    ) {
        val actual = session.getConfig(key)
        assertEquals("session config[$key]", expected, actual)
    }

    private fun assertNamesKnown(
        key: String,
        ours: String,
    ) {
        // 对照 JSch 的算法→实现类注册表 (config Hashtable 的 key 集), 而非默认提案列表:
        // ssh-rsa 有意不在 server_host_key 默认提案中但已注册 (SignatureRSA), 显式启用仍可用。
        val field = JSch::class.java.getDeclaredField("config")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val registry = field.get(null) as java.util.Hashtable<String, String>
        for (alg in ours.split(",")) {
            assertTrue(
                "algorithm '$alg' not registered under '$key' — kex 时会被过滤掉",
                registry.containsKey(alg),
            )
        }
    }
}
