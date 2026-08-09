package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 真机验证：导入的私钥 PEM 以 AES-GCM 密文落盘（不含明文特征），
 * 且新实例（无内存缓存）能从磁盘解密回读并交 JSch 使用。
 * 依赖 AndroidKeyStore，只能在设备/模拟器运行。
 */
@RunWith(AndroidJUnit4::class)
class ImportedKeyEncryptionTest {
    @Test
    fun `imported private key is stored encrypted and decrypts back on fresh instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alias = "import_enc_test_${System.currentTimeMillis()}"
        val fullAlias = "ssh_key_$alias"

        try {
            val pem = generatePem()
            SshKeyManager(context).importPrivateKey(alias, pem, passphrase = null)

            // 1) 落盘文件必须是密文: 不以明文 PEM 特征开头, 且字节不等于原始 PEM
            val keyFile = File(context.filesDir, "imported_keys/$fullAlias")
            assertTrue("key file must exist", keyFile.exists())
            val onDisk = keyFile.readBytes()
            assertTrue("encrypted blob must have IV prefix", onDisk.size > 12)
            val diskText = String(onDisk, Charsets.ISO_8859_1)
            assertFalse("must not contain PEM header", diskText.contains("BEGIN"))
            assertFalse("must not equal plaintext PEM", onDisk.contentEquals(pem.toByteArray(Charsets.UTF_8)))

            // 2) 新实例（无内存缓存）从磁盘解密: createJSchIdentity 内部走 loadImportedPem
            val freshManager = SshKeyManager(context)
            assertTrue(freshManager.hasKey(alias))
            assertTrue("public key must be recoverable from encrypted meta", freshManager.getPublicKey(alias).startsWith("ssh-"))
            val jsch = JSch()
            assertTrue("decrypted PEM must load into JSch", freshManager.createJSchIdentity(jsch, alias))
            assertNotNull(jsch.getIdentityRepository().getIdentities())
        } finally {
            SshKeyManager(context).deleteKey(alias)
        }
    }

    // 用 RSA 生成 PEM（标准 JCA，不依赖 BouncyCastle）；加密机制与算法无关
    private fun generatePem(): String {
        val jsch = JSch()
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA)
        try {
            val bos = ByteArrayOutputStream()
            keyPair.writePrivateKey(bos)
            return bos.toString(Charsets.UTF_8.name())
        } finally {
            keyPair.dispose()
        }
    }
}
