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
    fun `kex whitelist keeps ECDH for Android (MR-JAR stubs strip curve25519)`() {
        // Android 的 D8 不处理 jsch 的 META-INF/versions/*, 所以 curve25519 在真机上被
        // JSch 静默过滤; OpenSSH 10 默认又已移除有限域 DH → 没有 ECDH 就是必然协商失败。
        assertTrue(
            "ECDH must stay in the KEX whitelist",
            JschSshClient.KEX_ALGORITHMS.contains("ecdh-sha2-nistp256"),
        )
    }

    @Test
    fun `every whitelisted algorithm name is known to JSch`() {
        assertNamesKnown("kex", JschSshClient.KEX_ALGORITHMS)
        assertNamesKnown("server_host_key", JschSshClient.HOST_KEY_ALGORITHMS)
        assertNamesKnown("PubkeyAcceptedAlgorithms", JschSshClient.PUBKEY_ACCEPTED_ALGORITHMS)
        assertNamesKnown("cipher.c2s", JschSshClient.CIPHER_ALGORITHMS)
        assertNamesKnown("mac.c2s", JschSshClient.MAC_ALGORITHMS)
    }

    /**
     * 2026-11-03 真机回归: 出口 jz 的 sshd 只提供 PQ / curve25519 / ECDH 三类 kex。
     *
     * Android 上 JSch 的 X25519 (`DHXEC` 反射加载 XDH provider) 不可用 → 被静默过滤, 于是
     * 客户端提案只剩 `dh-group-exchange-sha256, group14-sha256`, 一个都不匹配 → 该出口永久
     * 建连失败。这条用例按"curve25519 全部不可用"的真实条件算交集, 白名单里必须还有别的路。
     */
    @Test
    fun `kex whitelist still negotiates a modern OpenSSH server without curve25519`() {
        val serverProposal =
            "mlkem768x25519-sha256,sntrup761x25519-sha512,sntrup761x25519-sha512@openssh.com," +
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
                "ext-info-s,kex-strict-s-v00@openssh.com"
        val withoutCurve25519 =
            JschSshClient.KEX_ALGORITHMS
                .split(",")
                .filterNot { it.contains("curve25519") }
                .toSet()
        val common =
            serverProposal
                .split(",")
                .filterNot { it.startsWith("ext-info") || it.startsWith("kex-strict") }
                .filter { it in withoutCurve25519 }
        assertTrue(
            "curve25519 不可用时白名单必须还能与只开 PQ/curve25519/ecdh 的服务器协商, 实际交集: $common",
            common.isNotEmpty(),
        )
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
