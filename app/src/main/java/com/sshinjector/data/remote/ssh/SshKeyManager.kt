package com.sshinjector.data.remote.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.JSch
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyManager @Inject constructor(
    private val context: Context
) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val aliasPrefix = "ssh_key_"

    private val publicKeyCache = mutableMapOf<String, String>()
    private val algorithmCache = mutableMapOf<String, String>()
    private val creationDateCache = mutableMapOf<String, String>()

    // Legacy ECDSA 格式支持 (部分服务器使用非标准格式)
    var useLegacyEcdsaFormat: Boolean = false // 使用标准 RFC 5656 格式
        set(value) {
            field = value
            AndroidKeyStoreIdentity.setLegacyEcdsaFormat(value)
            publicKeyCache.clear() // 清除缓存以重新生成
        }

    fun generateKeyPair(alias: String, algorithm: Int = 0, requireBiometric: Boolean = false): String {
        val fullAlias = "$aliasPrefix$alias"
        android.util.Log.d("SshKeyManager", "Generating key pair: $fullAlias, algo=$algorithm, biometric=$requireBiometric")

        if (keyStore.containsAlias(fullAlias)) {
            android.util.Log.w("SshKeyManager", "Key already exists: $fullAlias")
            throw IllegalStateException("密钥别名已存在: $fullAlias")
        }

        val (algoName, curveSpec) = when (algorithm) {
            0 -> "EC" to ECGenParameterSpec("secp256r1")
            1 -> "RSA" to null
            2 -> "EC" to ECGenParameterSpec("secp384r1")
            3 -> "Ed25519" to null
            else -> "EC" to ECGenParameterSpec("secp256r1")
        }

        val kg = KeyPairGenerator.getInstance(algoName, "AndroidKeyStore")
        val specBuilder = KeyGenParameterSpec.Builder(
            fullAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            curveSpec?.let { setAlgorithmParameterSpec(it) }
            setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
        }
        kg.initialize(specBuilder.build())
        val keyPair = kg.generateKeyPair()

        // 验证密钥已存储
        if (!keyStore.containsAlias(fullAlias)) {
            android.util.Log.e("SshKeyManager", "Key not stored after generation: $fullAlias")
            throw IllegalStateException("密钥存储失败")
        }

        val pubKeyStr = encodePublicKeyToOpenSSH(keyPair.public)
        publicKeyCache[fullAlias] = pubKeyStr
        algorithmCache[fullAlias] = when (algorithm) {
            0 -> "ECDSA P-256"
            1 -> "RSA 2048"
            2 -> "ECDSA P-384"
            3 -> "Ed25519"
            else -> "ECDSA P-256"
        }
        creationDateCache[fullAlias] = Date().toString().take(10)
        android.util.Log.d("SshKeyManager", "Key generated successfully: $fullAlias")
        return pubKeyStr
    }

    fun importPrivateKey(alias: String, privateKeyPem: String, passphrase: String? = null): String {
        val fullAlias = "$aliasPrefix$alias"
        val privateKey = parsePrivateKey(privateKeyPem, passphrase)
        importedKeys[fullAlias] = privateKey

        val kf = KeyFactory.getInstance(privateKey.algorithm)
        val pubKey = kf.generatePublic(X509EncodedKeySpec(privateKey.encoded))

        val pubKeyStr = encodePublicKeyToOpenSSH(pubKey)
        publicKeyCache[fullAlias] = pubKeyStr
        algorithmCache[fullAlias] = privateKey.algorithm
        creationDateCache[fullAlias] = Date().toString().take(10)
        return pubKeyStr
    }

    private val importedKeys = mutableMapOf<String, PrivateKey>()

    fun getPrivateKeyForAuth(alias: String): PrivateKey? {
        val fullAlias = "$aliasPrefix$alias"
        android.util.Log.d("SshKeyManager", "Getting private key for: $fullAlias")
        android.util.Log.d("SshKeyManager", "Imported keys: ${importedKeys.keys}")
        android.util.Log.d("SshKeyManager", "KeyStore aliases: ${keyStore.aliases().toList()}")

        return try {
            importedKeys[fullAlias] ?: run {
                if (keyStore.containsAlias(fullAlias)) {
                    android.util.Log.d("SshKeyManager", "Key exists in KeyStore, attempting to get entry")
                    val entry = keyStore.getEntry(fullAlias, null) as? KeyStore.PrivateKeyEntry
                    if (entry != null) {
                        android.util.Log.d("SshKeyManager", "Successfully got private key entry")
                        entry.privateKey
                    } else {
                        android.util.Log.e("SshKeyManager", "Failed to get PrivateKeyEntry")
                        null
                    }
                } else {
                    android.util.Log.e("SshKeyManager", "Key not found in KeyStore: $fullAlias")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SshKeyManager", "Failed to get private key: ${e.message}", e)
            null
        }
    }

    fun getPublicKey(alias: String): String {
        val fullAlias = "$aliasPrefix$alias"
        publicKeyCache[fullAlias]?.let { return it }

        val entry = keyStore.getEntry(fullAlias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("密钥不存在: $fullAlias")
        val pubKey = entry.certificate?.publicKey
            ?: throw IllegalStateException("无法提取公钥 (请重新生成密钥)")
        val str = encodePublicKeyToOpenSSH(pubKey)
        publicKeyCache[fullAlias] = str
        return str
    }

    fun getKeyAlgorithm(alias: String): String {
        val fullAlias = "$aliasPrefix$alias"
        algorithmCache[fullAlias]?.let { return it }

        val entry = keyStore.getEntry(fullAlias, null) as? KeyStore.PrivateKeyEntry
        return entry?.certificate?.publicKey?.algorithm ?: entry?.privateKey?.algorithm ?: "未知"
    }

    fun getKeyCreationDate(alias: String): String {
        val fullAlias = "$aliasPrefix$alias"
        creationDateCache[fullAlias]?.let { return it }
        return keyStore.getCreationDate(fullAlias)?.toString()?.take(10) ?: "未知"
    }

    fun hasKey(alias: String): Boolean {
        val fullAlias = "$aliasPrefix$alias"
        return keyStore.containsAlias(fullAlias) || importedKeys.containsKey(fullAlias)
    }

    fun deleteKey(alias: String) {
        val fullAlias = "$aliasPrefix$alias"
        keyStore.deleteEntry(fullAlias)
        importedKeys.remove(fullAlias)
        publicKeyCache.remove(fullAlias)
        algorithmCache.remove(fullAlias)
        creationDateCache.remove(fullAlias)
    }

    fun canAccessKey(alias: String): Boolean {
        return getPrivateKeyForAuth(alias) != null
    }

    fun deleteAllKeys() {
        val aliases = listKeyAliases()
        aliases.forEach { deleteKey(it) }
        android.util.Log.d("SshKeyManager", "Deleted ${aliases.size} keys")
    }

    fun listKeyAliases(): List<String> {
        return (keyStore.aliases().asSequence()
            .filter { it.startsWith(aliasPrefix) }
            .map { it.substring(aliasPrefix.length) }
            .toList() + importedKeys.keys.map { it.substring(aliasPrefix.length) })
            .distinct()
    }

    fun sign(alias: String, data: ByteArray): ByteArray {
        val privateKey = getPrivateKeyForAuth(alias)
            ?: throw IllegalStateException("无法访问私钥: $alias")
        val algorithm = when (privateKey.algorithm) {
            "EC" -> "SHA256withECDSA"
            "RSA" -> "SHA256withRSA"
            "Ed25519" -> "Ed25519"
            else -> "SHA256withECDSA"
        }
        val sig = java.security.Signature.getInstance(algorithm)
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    private fun parsePrivateKey(pem: String, passphrase: String?): PrivateKey {
        val cleaned = pem.trim()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN OPENSSH PRIVATE KEY-----", "")
            .replace("-----END OPENSSH PRIVATE KEY-----", "")
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val decoded = Base64.decode(cleaned, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(decoded)
        return KeyFactory.getInstance("EC").generatePrivate(spec)
    }

    private fun encodePublicKeyToOpenSSH(publicKey: PublicKey): String {
        val (algorithm, keyBytes) = when (publicKey) {
            is java.security.interfaces.ECPublicKey -> {
                val keyBits = publicKey.w.affineX.bitLength()
                val coordSize = if (keyBits > 320) 48 else 32
                val algName = if (coordSize == 48) "ecdsa-sha2-nistp384" else "ecdsa-sha2-nistp256"
                val curveName = if (coordSize == 48) "nistp384" else "nistp256"

                val x = publicKey.w.affineX.toByteArray().let { trimLeadingZero(it, coordSize) }
                val y = publicKey.w.affineY.toByteArray().let { trimLeadingZero(it, coordSize) }

                val blob = java.io.ByteArrayOutputStream()
                writeString(blob, algName.toByteArray())

                if (useLegacyEcdsaFormat) {
                    val point = ByteArray(coordSize * 2).also {
                        System.arraycopy(x, 0, it, 0, coordSize)
                        System.arraycopy(y, 0, it, coordSize, coordSize)
                    }
                    writeString(blob, point)
                } else {
                    writeString(blob, curveName.toByteArray())
                    val point = ByteArray(1 + coordSize * 2).also {
                        it[0] = 0x04
                        System.arraycopy(x, 0, it, 1, coordSize)
                        System.arraycopy(y, 0, it, 1 + coordSize, coordSize)
                    }
                    writeString(blob, point)
                }
                algName to blob.toByteArray()
            }
            is java.security.interfaces.RSAPublicKey -> {
                // RSA: SSH 格式是 [string 'ssh-rsa'] [mpint e] [mpint n]
                // 注意: X.509 格式是 (n, e)，SSH 格式是 (e, n)
                val blob = java.io.ByteArrayOutputStream()
                writeString(blob, "ssh-rsa".toByteArray())
                writeString(blob, publicKey.publicExponent.toByteArray())
                writeString(blob, publicKey.modulus.toByteArray())
                "ssh-rsa" to blob.toByteArray()
            }
            else -> {
                // Ed25519 或其他未知算法
                when (publicKey.algorithm) {
                    "Ed25519" -> {
                        // Ed25519: [string 'ssh-ed25519'] [string 32-byte key]
                        val encoded = publicKey.encoded
                        val ed25519KeyBytes = if (encoded.size >= 44) {
                            encoded.takeLast(32).toByteArray()
                        } else {
                            encoded
                        }
                        val blob = java.io.ByteArrayOutputStream()
                        writeString(blob, "ssh-ed25519".toByteArray())
                        writeString(blob, ed25519KeyBytes)
                        "ssh-ed25519" to blob.toByteArray()
                    }
                    else -> {
                        // 其他算法，尝试通用格式
                        val blob = java.io.ByteArrayOutputStream()
                        val algoBytes = publicKey.algorithm.toByteArray(StandardCharsets.US_ASCII)
                        writeString(blob, algoBytes)
                        writeString(blob, publicKey.encoded)
                        publicKey.algorithm to blob.toByteArray()
                    }
                }
            }
        }

        val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        return "$algorithm $encoded android-generated@${Date()}"
    }

    private fun writeString(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        val len = data.size
        out.write(len shr 24 and 0xFF)
        out.write(len shr 16 and 0xFF)
        out.write(len shr 8 and 0xFF)
        out.write(len and 0xFF)
        out.write(data)
    }

    private fun trimLeadingZero(bytes: ByteArray, targetSize: Int): ByteArray {
        val trimmed = if (bytes.size > targetSize && bytes[0].toInt() == 0) {
            bytes.copyOfRange(1, bytes.size)
        } else bytes
        if (trimmed.size == targetSize) return trimmed
        val result = ByteArray(targetSize)
        val offset = targetSize - trimmed.size
        System.arraycopy(trimmed, 0, result, offset, trimmed.size)
        return result
    }

    fun createJSchIdentity(jsch: JSch, alias: String): Boolean {
        val fullAlias = "$aliasPrefix$alias"
        val privateKey = getPrivateKeyForAuth(alias)
        if (privateKey == null) {
            android.util.Log.e("SshKeyManager", "Private key is null for alias: $alias")
            return false
        }

        try {
            android.util.Log.d("SshKeyManager", "Private key algorithm: ${privateKey.algorithm}")
            android.util.Log.d("SshKeyManager", "Private key format: ${privateKey.format}")

            // 尝试获取私钥编码
            val keyBytes = privateKey.encoded
            if (keyBytes != null) {
                android.util.Log.d("SshKeyManager", "Private key encoded length: ${keyBytes.size}")
                val pemPrivateKey = convertToPem("PRIVATE KEY", keyBytes)
                jsch.addIdentity(fullAlias, pemPrivateKey.toByteArray(), null, null)
                android.util.Log.d("SshKeyManager", "Added identity using PEM format")
                return true
            }

            // Hardware-backed key: .encoded is null, use AndroidKeyStoreIdentity
            android.util.Log.d("SshKeyManager", "Private key.encoded is null, using AndroidKeyStoreIdentity")
            val entry = keyStore.getEntry(fullAlias, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("KeyStore entry not found: $fullAlias")
            val cert = entry.certificate ?: throw IllegalStateException("Certificate not found: $fullAlias")
            val pubKey = cert.publicKey

            val publicKeyBytes = when (pubKey) {
                is java.security.interfaces.ECPublicKey -> AndroidKeyStoreIdentity.buildEcdsaPublicKeyBlob(pubKey)
                is java.security.interfaces.RSAPublicKey -> AndroidKeyStoreIdentity.buildRsaPublicKeyBlob(pubKey)
                is java.security.interfaces.EdECPublicKey -> AndroidKeyStoreIdentity.buildEd25519PublicKeyBlob(pubKey)
                else -> {
                    // 尝试通过算法名判断
                    when (pubKey.algorithm) {
                        "Ed25519" -> AndroidKeyStoreIdentity.buildEd25519PublicKeyBlob(pubKey)
                        else -> throw IllegalStateException("Unsupported key type: ${pubKey.algorithm}")
                    }
                }
            }

            val identity = AndroidKeyStoreIdentity(keyStore, fullAlias, privateKey, publicKeyBytes)
            jsch.addIdentity(identity, null)
            android.util.Log.d("SshKeyManager", "Added identity using AndroidKeyStoreIdentity")
            android.util.Log.d("SshKeyManager", "Public key blob hex: ${publicKeyBytes.joinToString("") { "%02x".format(it) }}")
            android.util.Log.d("SshKeyManager", "OpenSSH public key: ${encodePublicKeyToOpenSSH(cert.publicKey)}")
            return true
        } catch (e: Exception) {
            android.util.Log.e("SshKeyManager", "Failed to add identity to JSch: ${e.message}", e)
            return false
        }
    }

    private fun convertToPem(header: String, data: ByteArray): String {
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        return buildString {
            appendLine("-----BEGIN $header-----")
            encoded.chunked(64).forEach { line ->
                appendLine(line)
            }
            appendLine("-----END $header-----")
        }
    }
}