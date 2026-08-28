package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.srv0.sshinjector.data.local.database.AppDatabase
import cn.srv0.sshinjector.domain.model.ServerConfig
import cn.srv0.sshinjector.domain.usecase.ServerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机验证 CredentialCrypto 加解密往返：Keystore 仅存在于 Android 运行时，
 * JVM 单元测试无法覆盖，必须跑在设备/模拟器上。
 */
@RunWith(AndroidJUnit4::class)
class CredentialCryptoRoundTripTest {
    private val crypto = CredentialCrypto()

    @Test
    fun `encrypt then decrypt restores original password`() {
        val plain = "S3cret!密码😀 with spaces and special chars: @#$%^&*"
        val stored = crypto.encrypt(plain)
        assertTrue(crypto.isEncrypted(stored))
        assertNotEquals(plain, stored)
        assertEquals(plain, crypto.decrypt(stored))
    }

    @Test
    fun `empty and null values pass through unchanged`() {
        assertNull(crypto.encrypt(null))
        assertEquals("", crypto.encrypt(""))
        assertNull(crypto.decrypt(null))
        assertEquals("", crypto.decrypt(""))
    }

    @Test
    fun `encrypt is idempotent on already encrypted value`() {
        val stored = crypto.encrypt("pw")
        assertEquals(stored, crypto.encrypt(stored))
    }

    @Test
    fun `legacy plaintext decrypts as-is`() {
        assertEquals("legacy-pass", crypto.decrypt("legacy-pass"))
    }

    @Test
    fun `tampered or malformed ciphertext decrypts to null`() {
        assertNull(crypto.decrypt("enc:v1:AAAA"))
        val stored = crypto.encrypt("pw")!!
        assertNull(crypto.decrypt(stored.dropLast(4) + "AAAA"))
        assertNull(crypto.decrypt("enc:v1:!!!not-base64!!!"))
    }

    @Test
    fun `same plaintext yields different ciphertext each time`() {
        // GCM 随机 IV: 同一明文两次加密结果不同
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"))
    }

    @Test
    fun `server repository round trip stores encrypted and decrypts on read`() =
        runBlocking {
            val db =
                Room
                    .inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext<Context>(),
                        AppDatabase::class.java,
                    ).allowMainThreadQueries()
                    .build()
            try {
                val repo = ServerRepository(db.serverDao(), db.whitelistDao(), crypto)
                val id =
                    repo.saveServer(
                        ServerConfig(
                            name = "t",
                            host = "example.com",
                            username = "root",
                            keyAlias = "k",
                            password = "P@ss!w0rd",
                        ),
                    )
                val raw = db.serverDao().getByIdBlocking(id)
                assertTrue(crypto.isEncrypted(raw?.password))
                assertNotEquals("P@ss!w0rd", raw?.password)
                assertEquals("P@ss!w0rd", repo.getServerById(id)?.password)
            } finally {
                db.close()
            }
        }
}
