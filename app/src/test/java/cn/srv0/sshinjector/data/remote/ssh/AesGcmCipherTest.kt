package cn.srv0.sshinjector.data.remote.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AesGcmCipher 加解密往返的 JVM 级验证（真实 AES-256 密钥，非 mock）。
 */
class AesGcmCipherTest {
    private lateinit var cipher: AesGcmCipher

    @Before
    fun setUp() {
        cipher = AesGcmCipher(newAesKey())
    }

    @Test
    fun `round trip restores original password`() {
        val plain = "S3cret!密码😀 with spaces and special chars: @#$%^&*"
        val stored = cipher.encrypt(plain)
        assertTrue(cipher.isEncrypted(stored))
        assertNotEquals(plain, stored)
        assertEquals(plain, cipher.decrypt(stored))
    }

    @Test
    fun `empty and null values pass through unchanged`() {
        assertNull(cipher.encrypt(null))
        assertEquals("", cipher.encrypt(""))
        assertNull(cipher.decrypt(null))
        assertEquals("", cipher.decrypt(""))
    }

    @Test
    fun `encrypt is idempotent on already encrypted value`() {
        val stored = cipher.encrypt("pw")
        assertEquals(stored, cipher.encrypt(stored))
    }

    @Test
    fun `legacy plaintext decrypts as-is`() {
        assertEquals("legacy-pass", cipher.decrypt("legacy-pass"))
    }

    @Test
    fun `tampered or malformed ciphertext decrypts to null`() {
        assertNull(cipher.decrypt("enc:v1:AAAA"))
        val stored = cipher.encrypt("pw")!!
        assertNull(cipher.decrypt(stored.dropLast(4) + "AAAA"))
        assertNull(cipher.decrypt("enc:v1:!!!not-base64!!!"))
    }

    @Test
    fun `same plaintext yields different ciphertext each time`() {
        // GCM 随机 IV: 同一明文两次加密结果不同
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `ciphertext encrypted with a different key fails to decrypt`() {
        val other = AesGcmCipher(newAesKey())
        assertNull(other.decrypt(cipher.encrypt("pw")))
    }

    private fun newAesKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }
}
