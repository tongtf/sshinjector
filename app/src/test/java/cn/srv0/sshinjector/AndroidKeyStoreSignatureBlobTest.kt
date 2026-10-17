package cn.srv0.sshinjector

import cn.srv0.sshinjector.data.remote.ssh.buildEcdsaSignatureBlob
import cn.srv0.sshinjector.data.remote.ssh.buildEd25519SignatureBlob
import cn.srv0.sshinjector.data.remote.ssh.buildRawSignatureBlob
import cn.srv0.sshinjector.data.remote.ssh.ecdsaDigestForCurveSize
import cn.srv0.sshinjector.data.remote.ssh.rsaSignAlg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T-04: Android Keystore 硬件密钥必须产出符合 SSH 规范的签名 blob
 * (`string alg ‖ string rawSignature`), 而不是裸签名。
 * 只测抽出的纯函数 (不触 Keystore, 无法在 JVM 单测)。
 */
class AndroidKeyStoreSignatureBlobTest {
    @Test
    fun `rsa signature blob is string alg + string sig`() {
        val rawSig = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D)
        val alg = "rsa-sha2-256"
        val expected = sshString(alg.toByteArray(Charsets.UTF_8)) + sshString(rawSig)
        assertArrayEquals(expected, buildRawSignatureBlob(alg, rawSig))
    }

    @Test
    fun `rsa signature is not raw`() {
        val algs = listOf("rsa-sha2-256", "rsa-sha2-512", "ssh-rsa")
        val rawSig = ByteArray(256) { (it and 0xFF).toByte() }
        for (alg in algs) {
            val blob = buildRawSignatureBlob(alg, rawSig)
            // 长度必须大于裸签名, 且头 4 字节是 alg 的 big-endian 长度前缀
            assertTrue("blob must wrap the raw sig for $alg", blob.size > rawSig.size)
            assertEquals(alg.toByteArray(Charsets.UTF_8).size.toLong(), readU32BE(blob, 0))
            assertEquals(alg, firstSshString(blob))
            // 前 12 字节应等于 `len ‖ alg`（"rsa-sha2-256" 长度 = 0x0c）
            assertArrayEquals(sshString(alg.toByteArray(Charsets.UTF_8)), blob.copyOfRange(0, 4 + alg.length))
        }
    }

    @Test
    fun `ec blob outer string equals negotiated alg`() {
        // DER SEQUENCE { INTEGER 1, INTEGER 2 }
        val derSig = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)
        val blob = buildEcdsaSignatureBlob("ecdsa-sha2-nistp384", derSig)
        assertEquals("ecdsa-sha2-nistp384", firstSshString(blob))
    }

    @Test
    fun `ed25519 blob outer string is ssh-ed25519`() {
        val rawSig = ByteArray(64) { (it and 0xFF).toByte() }
        val blob = buildEd25519SignatureBlob("ssh-ed25519", rawSig)
        assertEquals("ssh-ed25519", firstSshString(blob))
        // 64 字节裸签名原样透传: 外层 string alg + 内层 string rawSig
        assertArrayEquals(sshString("ssh-ed25519".toByteArray(Charsets.UTF_8)) + sshString(rawSig), blob)
    }

    @Test
    fun `curve size selects digest matching declared curve`() {
        assertEquals("SHA256withECDSA", ecdsaDigestForCurveSize(256))
        assertEquals("SHA384withECDSA", ecdsaDigestForCurveSize(384))
        assertEquals("SHA384withECDSA", ecdsaDigestForCurveSize(521))
    }

    @Test
    fun `rsa negotiated alg maps to jca digest`() {
        assertEquals("SHA512withRSA", rsaSignAlg("rsa-sha2-512"))
        assertEquals("SHA256withRSA", rsaSignAlg("rsa-sha2-256"))
        assertEquals("SHA1withRSA", rsaSignAlg("ssh-rsa"))
    }

    /** Tiny SSH string encoder: big-endian u32 length prefix + data. */
    private fun sshString(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(len ushr 24 and 0xFF)
        out.write(len ushr 16 and 0xFF)
        out.write(len ushr 8 and 0xFF)
        out.write(len and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    /** First SSH string of a blob, decoded as UTF-8. */
    private fun firstSshString(blob: ByteArray): String {
        val len = readU32BE(blob, 0).toInt()
        return String(blob, 4, len, Charsets.UTF_8)
    }

    /** Read a big-endian u32 at [offset] as an unsigned long. */
    private fun readU32BE(
        buf: ByteArray,
        offset: Int,
    ): Long =
        ByteBuffer
            .wrap(buf, offset, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .toLong() and 0xFFFF_FFFFL
}
