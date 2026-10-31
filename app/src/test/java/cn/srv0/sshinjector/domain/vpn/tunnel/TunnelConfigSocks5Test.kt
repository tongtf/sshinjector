package cn.srv0.sshinjector.domain.vpn.tunnel

import cn.srv0.sshinjector.domain.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C-1 回归: 运行时隧道路径必须把服务器行 `id` 与 `hostKeyFingerprint` 透传下去。
 *
 * 丢了 `id` → `JschSshClient.persistFingerprint` 的 `id <= 0` 早退, 首次 TOFU 指纹永远落不了库;
 * 丢了 `hostKeyFingerprint` → DB 里已保存的指纹不参与比对, "指纹变了必须拒绝"形同虚设。
 * 两者都是静默失效 (连接照常成功), 只能靠单测钉住。
 */
class TunnelConfigSocks5Test {
    private fun server(
        id: Long = 7,
        fingerprint: String? = "SHA256:abc",
    ) = ServerConfig(
        id = id,
        name = "hk",
        host = "hk.example.com",
        port = 22,
        username = "tester",
        keyAlias = "key-1",
        hostKeyFingerprint = fingerprint,
    )

    @Test
    fun `forSocks5 passes server id and fingerprint`() {
        val config = TunnelConfig.forSocks5(server())

        assertEquals(7L, config.serverId)
        assertEquals("SHA256:abc", config.hostKeyFingerprint)
    }

    @Test
    fun `forSocks5 keeps id 0 for transient configs so fingerprint is not persisted`() {
        val config = TunnelConfig.forSocks5(server(id = 0, fingerprint = null))

        assertEquals(0L, config.serverId)
        assertNull(config.hostKeyFingerprint)
    }

    @Test
    fun `Socks5 defaults are transient and fingerprint-free`() {
        val config =
            TunnelConfig.Socks5(
                sshHost = "1.2.3.4",
                sshUsername = "u",
                sshKeyAlias = "k",
            )

        assertEquals(0L, config.serverId)
        assertNull(config.hostKeyFingerprint)
    }
}
