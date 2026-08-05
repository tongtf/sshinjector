package cn.srv0.sshinjector.data.remote.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.JSch
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** 密钥来源类型: 本地生成 / 导入私钥 / 仅导入公钥 */
enum class KeyKind { GENERATED, IMPORTED_PRIVATE, IMPORTED_PUBLIC }

@Singleton
class SshKeyManager @Inject constructor(
    private val context: Context
) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val aliasPrefix = "ssh_key_"
    private val importWrapperAlias = "ssh_import_wrapper_aes"
    private val importedKeysDir: File = File(context.filesDir, "imported_keys").apply { mkdirs() }
    private val generatedKeysDir: File = File(context.filesDir, "generated_keys").apply { mkdirs() }

    private val publicKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val algorithmCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val creationDateCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Legacy ECDSA 格式支持 (部分服务器使用非标准格式)
    var useLegacyEcdsaFormat: Boolean = false // 使用标准 RFC 5656 格式
        set(value) {
            field = value
            AndroidKeyStoreIdentity.setLegacyEcdsaFormat(value)
            publicKeyCache.clear() // 清除缓存以重新生成
        }

    fun generateKeyPair(alias: String, algorithm: Int = 0, requireBiometric: Boolean = false): String {
        val fullAlias = "$aliasPrefix$alias"
        if (keyStore.containsAlias(fullAlias)) {
            android.util.Log.w("SshKeyManager", "Key already exists")
            throw IllegalStateException("密钥别名已存在")
        }

        val keyPair = if (algorithm == 3) {
            // Ed25519: 强制使用 AndroidKeyStore (API 31+)，禁用软件密钥降级以保护私钥
            val kg = try {
                KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
            } catch (e: Exception) {
                throw IllegalStateException("设备不支持 Ed25519 硬件密钥, 请改用 ECDSA P-256")
            }
            kg.initialize(
                KeyGenParameterSpec.Builder(
                    fullAlias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).apply {
                    setDigests(
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512
                    )
                    if (requireBiometric) {
                        setUserAuthenticationRequired(true)
                        setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    }
                }.build()
            )
            kg.generateKeyPair()
        } else {
            val (algoName, curveSpec) = when (algorithm) {
                0 -> "EC" to ECGenParameterSpec("secp256r1")
                1 -> "RSA" to null
                2 -> "EC" to ECGenParameterSpec("secp384r1")
                else -> "EC" to ECGenParameterSpec("secp256r1")
            }

            val kg = KeyPairGenerator.getInstance(algoName, "AndroidKeyStore")
            val specBuilder = KeyGenParameterSpec.Builder(
                fullAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).apply {
                curveSpec?.let { setAlgorithmParameterSpec(it) }
                if (algorithm == 1) setKeySize(2048)
                setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512
                )
                if (requireBiometric) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            kg.initialize(specBuilder.build())
            kg.generateKeyPair()
        }

        // Ed25519: 可能在 AndroidKeyStore 或文件中
        if (algorithm == 3) {
            if (!keyStore.containsAlias(fullAlias)) {
                // 不在 AndroidKeyStore，使用 AES-GCM 加密后持久化到文件
                val safeName = fullAlias.replace("/", "_")
                val wrappingKey = getOrCreateImportWrapperKey()
                val privKeyBytes = keyPair.private.encoded

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
                val ciphertext = cipher.doFinal(privKeyBytes)

                File(generatedKeysDir, safeName).writeBytes(cipher.iv + ciphertext)
                File(generatedKeysDir, "$safeName.pub").writeBytes(keyPair.public.encoded)
            }
        } else if (!keyStore.containsAlias(fullAlias)) {
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
        val safeName = fullAlias.replace("/", "_")
        if (File(importedKeysDir, safeName).exists()) {
            throw IllegalStateException("密钥别名已存在: $fullAlias")
        }

        // 用 JSch 解析私钥 (支持 OpenSSH/PKCS8), 并派生标准 SSH 公钥
        val (pubKeyStr, algorithm) = parsePrivateKeyViaJsch(privateKeyPem, passphrase)

        persistImportedKey(fullAlias, privateKeyPem, passphrase, pubKeyStr, algorithm)
        importedPemCache[fullAlias] = ImportedPem(
            privateKeyPem,
            passphrase?.toByteArray(StandardCharsets.UTF_8),
            algorithm
        )
        // 限制缓存大小，移除最早的条目
        while (importedPemCache.size > MAX_PEM_CACHE_SIZE) {
            importedPemCache.keys.firstOrNull()?.let { importedPemCache.remove(it) }
        }
        publicKeyCache[fullAlias] = pubKeyStr
        algorithmCache[fullAlias] = algorithm
        creationDateCache[fullAlias] = Date().toString().take(10)
        return pubKeyStr
    }

    /**
     * 仅导入公钥 (无私钥), 用于服务器授权而不在本机保存私钥。
     */
    fun importPublicKey(alias: String, publicKeySsh: String): String {
        val fullAlias = "$aliasPrefix$alias"
        val safeName = fullAlias.replace("/", "_")
        if (File(importedKeysDir, safeName).exists() ||
            File(importedKeysDir, "$safeName.pub").exists() ||
            keyStore.containsAlias(fullAlias)
        ) {
            throw IllegalStateException("密钥别名已存在: $fullAlias")
        }

        val typeName = publicKeySsh.substringBefore(' ').trim()
        val algorithm = when (typeName) {
            "ssh-rsa" -> "RSA"
            "ssh-ed25519" -> "Ed25519"
            else -> if (typeName.startsWith("ecdsa-")) "ECDSA" else typeName.ifEmpty { "未知" }
        }

        File(importedKeysDir, "$safeName.pub").writeText(publicKeySsh.trim() + "\n", StandardCharsets.UTF_8)
        File(importedKeysDir, "$safeName.pub.meta").writeText(
            "$algorithm\n${publicKeySsh.trim()}\n",
            StandardCharsets.UTF_8
        )
        publicKeyCache[fullAlias] = publicKeySsh.trim()
        algorithmCache[fullAlias] = algorithm
        creationDateCache[fullAlias] = Date().toString().take(10)
        return publicKeySsh.trim()
    }

    fun getKeyKind(alias: String): KeyKind {
        val fullAlias = "$aliasPrefix$alias"
        val safeName = fullAlias.replace("/", "_")
        return when {
            File(importedKeysDir, safeName).exists() -> KeyKind.IMPORTED_PRIVATE
            File(importedKeysDir, "$safeName.pub").exists() -> KeyKind.IMPORTED_PUBLIC
            else -> KeyKind.GENERATED
        }
    }

    /**
     * 解析私钥 PEM (OpenSSH/PKCS8), 用 JSch 提取 SSH 公钥文本与算法名。
     * @return Pair<公钥SSH文本, 算法名>
     */
    private fun parsePrivateKeyViaJsch(pem: String, passphrase: String?): Pair<String, String> {
        val jsch = JSch()
        val pass = passphrase?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val keyPair = try {
            com.jcraft.jsch.KeyPair.load(jsch, pem.toByteArray(), pass)
        } catch (e: Exception) {
            throw IllegalStateException("无法解析私钥: ${e.message}")
        }
        try {
            val blob = keyPair.getPublicKeyBlob() ?: throw IllegalStateException("无法提取公钥")
            val typeName = keyPair.getKeyTypeString()
            val pub = "$typeName ${Base64.encodeToString(blob, Base64.NO_WRAP)}"
            val algorithm = when (typeName) {
                "ssh-rsa" -> "RSA"
                "ssh-ed25519" -> "Ed25519"
                else -> {
                    if (typeName.startsWith("ecdsa-")) "ECDSA" else typeName
                }
            }
            return pub to algorithm
        } finally {
            runCatching { keyPair.dispose() }
        }
    }

    /** 用 Keystore AES 包装密钥加密导入的私钥 PEM 并落盘 */
    private fun persistImportedKey(
        fullAlias: String,
        pem: String,
        passphrase: String?,
        pubKeyStr: String,
        algorithm: String
    ) {
        val wrappingKey = getOrCreateImportWrapperKey()
        // 载荷: [passLen 4B][passBytes][pemBytes]
        val passBytes = passphrase?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        val pemBytes = pem.toByteArray(StandardCharsets.UTF_8)
        val payload = java.io.ByteArrayOutputStream().apply {
            write(passBytes.size shr 24 and 0xFF)
            write(passBytes.size shr 16 and 0xFF)
            write(passBytes.size shr 8 and 0xFF)
            write(passBytes.size and 0xFF)
            write(passBytes)
            write(pemBytes)
        }.toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val ciphertext = cipher.doFinal(payload)

        val safeName = fullAlias.replace("/", "_")
        val file = File(importedKeysDir, safeName)
        file.writeBytes(cipher.iv + ciphertext)
        writeEncryptedMetaFile(File(importedKeysDir, "$safeName.meta"), "$algorithm\n$pubKeyStr\n")
    }

    /** AES-GCM 加密写 .meta 文件 */
    private fun writeEncryptedMetaFile(file: File, content: String) {
        val wrappingKey = getOrCreateImportWrapperKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val ciphertext = cipher.doFinal(content.toByteArray(StandardCharsets.UTF_8))
        file.writeBytes(cipher.iv + ciphertext)
    }

    /** 读取 .meta 文件: 优先解密, 兼容旧版明文 */
    private fun readEncryptedMetaFile(file: File): String? {
        if (!file.exists()) return null
        val data = file.readBytes()
        if (data.size < 12) return null
        // 旧版明文格式以可打印文本开头, 无 IV 前缀; 尝试解密, 失败则按明文处理
        return runCatching {
            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)
            val wrappingKey = getOrCreateImportWrapperKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrElse {
            data.toString(StandardCharsets.UTF_8)
        }
    }

    private fun getOrCreateImportWrapperKey(): SecretKey {
        if (keyStore.containsAlias(importWrapperAlias)) {
            val entry = keyStore.getEntry(importWrapperAlias, null) as? KeyStore.SecretKeyEntry
                ?: throw IllegalStateException("无法读取导入密钥包装密钥")
            return entry.secretKey
        }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                importWrapperAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** 导入的原始 PEM + 口令 + 算法 (内存缓存) */
    private class ImportedPem(val pem: String, val passphrase: ByteArray?, val algorithm: String)

    private val importedPemCache = java.util.concurrent.ConcurrentHashMap<String, ImportedPem>()

    // PEM 缓存最大条目数，防止内存泄漏
    private val MAX_PEM_CACHE_SIZE = 10

    // 导入私钥解密后 payload 上限 (PEM+口令)，防止解密出异常大对象
    private val MAX_IMPORTED_KEY_SIZE = 4096

    /** 从磁盘解密并加载导入的私钥 PEM, 未找到返回 null */
    private fun loadImportedPem(fullAlias: String): ImportedPem? {
        importedPemCache[fullAlias]?.let { return it }
        val safeName = fullAlias.replace("/", "_")
        val file = File(importedKeysDir, safeName)
        if (!file.exists()) return null
        return runCatching {
            val data = file.readBytes()
            if (data.size < 12) return null
            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)
            val wrappingKey = getOrCreateImportWrapperKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
            val payload = cipher.doFinal(ciphertext)
            if (payload.size > MAX_IMPORTED_KEY_SIZE) return null
            val passLen = ((payload[0].toInt() and 0xFF) shl 24) or
                ((payload[1].toInt() and 0xFF) shl 16) or
                ((payload[2].toInt() and 0xFF) shl 8) or
                (payload[3].toInt() and 0xFF)
            var pos = 4
            if (passLen < 0 || pos + passLen > payload.size) return null
            val pass = payload.copyOfRange(pos, pos + passLen)
            pos += passLen
            val pem = String(payload.copyOfRange(pos, payload.size), StandardCharsets.UTF_8)
            val algorithm = readImportedAlgorithm(safeName)
            ImportedPem(pem, if (passLen > 0) pass else null, algorithm).also {
                importedPemCache[fullAlias] = it
            }
        }.getOrNull()
    }

    private fun readImportedAlgorithm(safeName: String): String {
        val metaFile = File(importedKeysDir, "$safeName.meta")
        return readEncryptedMetaFile(metaFile)?.lineSequence()?.firstOrNull() ?: "ECDSA"
    }

    fun getPrivateKeyForAuth(alias: String): PrivateKey? {
        val fullAlias = "$aliasPrefix$alias"

        return try {
            // 导入密钥由 JSch 用 PEM 直接签名, 不走 JCA PrivateKey
            if (loadImportedPem(fullAlias) != null) {
                return null
            }
            // Ed25519 密钥存储在文件中
            val safeName = fullAlias.replace("/", "_")
            val keyFile = File(generatedKeysDir, safeName)
            if (keyFile.exists()) {
                android.util.Log.d("SshKeyManager", "Loading Ed25519 key from file")
                val keyBytes = keyFile.readBytes()
                val kf = KeyFactory.getInstance("Ed25519")
                return kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))
            }
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
                android.util.Log.e("SshKeyManager", "Key not found in KeyStore")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("SshKeyManager", "Failed to get private key: ${e.message}", e)
            null
        }
    }

    fun getPublicKey(alias: String): String {
        val fullAlias = "$aliasPrefix$alias"
        publicKeyCache[fullAlias]?.let { return it }

        // 导入私钥: 从 meta 文件恢复公钥
        val safeName = fullAlias.replace("/", "_")
        val metaFile = File(importedKeysDir, "$safeName.meta")
        val metaContent = readEncryptedMetaFile(metaFile)
        if (metaContent != null) {
            val lines = metaContent.lineSequence().toList()
            if (lines.size >= 2) {
                val str = lines[1]
                publicKeyCache[fullAlias] = str
                return str
            }
        }

        // 仅公钥: 直接读 .pub 文件
        val pubFile = File(importedKeysDir, "$safeName.pub")
        if (pubFile.exists()) {
            val str = pubFile.readText(StandardCharsets.UTF_8).trim()
            publicKeyCache[fullAlias] = str
            return str
        }

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

        val safeName = fullAlias.replace("/", "_")
        val metaFile = File(importedKeysDir, "$safeName.meta")
        val pubMetaFile = File(importedKeysDir, "$safeName.pub.meta")
        val firstLine = when {
            metaFile.exists() -> readEncryptedMetaFile(metaFile)?.lineSequence()?.firstOrNull()
            pubMetaFile.exists() -> pubMetaFile.readText(StandardCharsets.UTF_8).lineSequence().firstOrNull()
            else -> null
        }
        if (firstLine != null) return firstLine

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
        if (keyStore.containsAlias(fullAlias)) return true
        val safeName = fullAlias.replace("/", "_")
        return File(importedKeysDir, safeName).exists() || File(importedKeysDir, "$safeName.pub").exists()
    }

    fun deleteKey(alias: String) {
        val fullAlias = "$aliasPrefix$alias"
        keyStore.deleteEntry(fullAlias)
        importedPemCache.remove(fullAlias)
        publicKeyCache.remove(fullAlias)
        algorithmCache.remove(fullAlias)
        creationDateCache.remove(fullAlias)
        val safeName = fullAlias.replace("/", "_")
        File(importedKeysDir, safeName).delete()
        File(importedKeysDir, "$safeName.meta").delete()
        File(importedKeysDir, "$safeName.pub").delete()
        File(importedKeysDir, "$safeName.pub.meta").delete()
        // 删除 Ed25519 密钥文件
        File(generatedKeysDir, safeName).delete()
        File(generatedKeysDir, "$safeName.pub").delete()
    }

    fun canAccessKey(alias: String): Boolean {
        return getPrivateKeyForAuth(alias) != null
    }

    /**
     * 查询密钥是否要求用户认证(生物识别/锁屏)才能签名。
     */
    fun isBiometricProtected(alias: String): Boolean {
        val fullAlias = "$aliasPrefix$alias"
        return runCatching {
            val entry = keyStore.getEntry(fullAlias, null) as? KeyStore.PrivateKeyEntry ?: return false
            val kf = KeyFactory.getInstance(entry.privateKey.algorithm, "AndroidKeyStore")
            val keyInfo = kf.getKeySpec(entry.privateKey, android.security.keystore.KeyInfo::class.java)
            keyInfo.isUserAuthenticationRequired
        }.getOrDefault(false)
    }

    fun deleteAllKeys() {
        val aliases = listKeyAliases()
        aliases.forEach { deleteKey(it) }
        android.util.Log.d("SshKeyManager", "Deleted ${aliases.size} keys")
    }

    fun listKeyAliases(): List<String> {
        val keystoreAliases = keyStore.aliases().asSequence()
            .filter { it.startsWith(aliasPrefix) }
            .map { it.substring(aliasPrefix.length) }
        val importedFileAliases = importedKeysDir.listFiles()?.asSequence()
            ?.filter { it.isFile && !it.name.endsWith(".meta") }
            ?.map {
                val name = if (it.name.endsWith(".pub")) it.name.removeSuffix(".pub") else it.name
                name.substring(aliasPrefix.length)
            }
            ?: emptySequence()
        return (keystoreAliases + importedFileAliases).distinct().toList()
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

        // 导入密钥: 直接交 JSch 用原始 PEM 解析 (支持 OpenSSH/PKCS8/加密)
        loadImportedPem(fullAlias)?.let { imported ->
            return try {
                jsch.addIdentity(
                    fullAlias,
                    imported.pem.toByteArray(StandardCharsets.UTF_8),
                    null,
                    imported.passphrase
                )
                android.util.Log.d("SshKeyManager", "Added imported identity")
                true
            } catch (e: Exception) {
                android.util.Log.e("SshKeyManager", "Failed to add imported identity: ${e.message}", e)
                false
            }
        }

        val privateKey = getPrivateKeyForAuth(alias)
        if (privateKey == null) {
            android.util.Log.e("SshKeyManager", "Private key is null")
            return false
        }

        try {
            logKeyAuthInfo(fullAlias, privateKey)

            // 尝试获取私钥编码
            val keyBytes = privateKey.encoded
            if (keyBytes != null) {
                val pemPrivateKey = convertToPem("PRIVATE KEY", keyBytes)
                jsch.addIdentity(fullAlias, pemPrivateKey.toByteArray(), null, null)
                android.util.Log.d("SshKeyManager", "Added identity using PEM format")
                return true
            }

            // Hardware-backed key: .encoded is null, use AndroidKeyStoreIdentity
            android.util.Log.d("SshKeyManager", "Private key.encoded is null, using AndroidKeyStoreIdentity")
            val entry = keyStore.getEntry(fullAlias, null) as? KeyStore.PrivateKeyEntry
                ?: throw IllegalStateException("KeyStore entry not found")
            val cert = entry.certificate ?: throw IllegalStateException("Certificate not found")
            val pubKey = cert.publicKey

            val publicKeyBytes = when (pubKey) {
                is java.security.interfaces.ECPublicKey -> AndroidKeyStoreIdentity.buildEcdsaPublicKeyBlob(pubKey)
                is java.security.interfaces.RSAPublicKey -> AndroidKeyStoreIdentity.buildRsaPublicKeyBlob(pubKey)
                is java.security.interfaces.EdECPublicKey -> AndroidKeyStoreIdentity.buildEd25519PublicKeyBlob(pubKey)
                else -> {
                    // 尝试通过算法名判断
                    when (pubKey.algorithm) {
                        "Ed25519" -> AndroidKeyStoreIdentity.buildEd25519PublicKeyBlob(pubKey)
                        else -> throw IllegalStateException("Unsupported key type")
                    }
                }
            }

            val identity = AndroidKeyStoreIdentity(keyStore, fullAlias, privateKey, publicKeyBytes)
            jsch.addIdentity(identity, null)
            android.util.Log.d("SshKeyManager", "Added identity using AndroidKeyStoreIdentity")
            return true
        } catch (e: Exception) {
            android.util.Log.e("SshKeyManager", "Failed to add identity to JSch: ${e.message}", e)
            return false
        }
    }

    private fun logKeyAuthInfo(fullAlias: String, privateKey: PrivateKey) {
        try {
            val kf = KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
            val keyInfo = kf.getKeySpec(privateKey, android.security.keystore.KeyInfo::class.java)
            if (keyInfo != null) {
                android.util.Log.d(
                    "SshKeyManager",
                    "KeyInfo: authRequired=${keyInfo.isUserAuthenticationRequired}, " +
                        "authType=0x${keyInfo.userAuthenticationType.toString(16)}, " +
                        "authValidityForEncryption=${keyInfo.userAuthenticationValidityDurationSeconds}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.d("SshKeyManager", "KeyInfo unavailable: ${e.message}")
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