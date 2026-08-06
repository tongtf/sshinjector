package cn.srv0.sshinjector

import org.junit.Assert.assertEquals
import org.junit.Test

class SshKeyManagerTest {
    @Test
    fun `test key alias prefix`() {
        val prefix = "ssh_key_"
        val alias = "test_server"
        val fullAlias = "$prefix$alias"
        assertEquals("ssh_key_test_server", fullAlias)
    }

    @Test
    fun `test key algorithm names`() {
        val algorithms =
            mapOf(
                0 to "Ed25519",
                1 to "RSA 4096",
                2 to "ECDSA P-256",
            )
        assertEquals("Ed25519", algorithms[0])
        assertEquals("RSA 4096", algorithms[1])
        assertEquals("ECDSA P-256", algorithms[2])
    }
}
