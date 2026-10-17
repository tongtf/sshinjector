package cn.srv0.sshinjector.data.remote.ssh

import android.util.Log
import com.jcraft.jsch.Identity
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * JSch Identity backed by Android Keystore.
 * Used when private key material cannot be exported (hardware-backed keys).
 * Signs via KeyStore instead of raw private key bytes.
 */
class AndroidKeyStoreIdentity(
    private val fullAlias: String,
    private val privateKey: PrivateKey,
    private val publicKeyBytes: ByteArray,
) : Identity {
    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun getPublicKeyBlob(): ByteArray = publicKeyBytes

    override fun getName(): String = fullAlias

    override fun getSignature(data: ByteArray?): ByteArray? = getSignature(data, getAlgName())

    /**
     * JSch calls this 2-arg overload during userauth / RSA SHA-2 negotiation and passes the
     * negotiated signature algorithm (`ssh-rsa`, `rsa-sha2-256`, `rsa-sha2-512`,
     * `ecdsa-sha2-nistpXXX`, `ssh-ed25519`). It expects a full SSH signature blob
     * `string alg ‖ string rawSignature` — exactly what JSch's own `KeyPairRSA.getSignature`
     * produces. Returning a raw signature here makes RSA auth fail.
     */
    override fun getSignature(
        data: ByteArray?,
        alg: String?,
    ): ByteArray? {
        if (data == null) {
            Log.w("AndroidKeyStoreIdentity", "getSignature called with null data")
            return null
        }
        val signatureAlg = alg ?: getAlgName()
        return try {
            val rawSig = signRaw(data, signatureAlg)
            when (privateKey.algorithm) {
                "EC" -> buildEcdsaSignatureBlob(signatureAlg, rawSig)
                "Ed25519" -> buildEd25519SignatureBlob(signatureAlg, rawSig)
                else -> buildRawSignatureBlob(signatureAlg, rawSig)
            }
        } catch (e: Exception) {
            Log.e(
                "AndroidKeyStoreIdentity",
                "Sign failed: alg=$signatureAlg key=${privateKey.algorithm}: ${e.message}",
                e,
            )
            null
        }
    }

    private fun signRaw(
        data: ByteArray,
        alg: String,
    ): ByteArray {
        val sigAlgorithm =
            when (privateKey.algorithm) {
                "EC" -> ecdsaDigestForCurveSize(ecCurveBitLength())
                "RSA" -> rsaSignAlg(alg)
                "Ed25519" -> "Ed25519"
                else -> "SHA256withECDSA"
            }
        val sig = Signature.getInstance(sigAlgorithm)
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    /**
     * 从 EC 私钥的 ECParameterSpec 获取曲线位数, 避免强转 ECPublicKey 失败。
     * 与 [getAlgName] 同源, 保证声明的曲线与签名摘要一致。
     */
    private fun ecCurveBitLength(): Int =
        try {
            (privateKey as? java.security.interfaces.ECKey)?.params?.order?.bitLength() ?: 256
        } catch (_: Exception) {
            256
        }

    override fun getAlgName(): String =
        when (privateKey.algorithm) {
            "EC" -> if (ecCurveBitLength() > ECDSA_P384_MIN_BITS) "ecdsa-sha2-nistp384" else "ecdsa-sha2-nistp256"
            "RSA" -> "ssh-rsa"
            "Ed25519" -> "ssh-ed25519"
            else -> privateKey.algorithm
        }

    override fun isEncrypted(): Boolean = false

    override fun clear() {
        // JSch 调用 clear() 表示不再需要此身份
        // 私钥由 Android Keystore 管理, 无需手动清除
    }

    companion object {
        // 公钥 blob 格式: STANDARD (RFC 5656) 或 LEGACY (部分服务器)
        private var useLegacyEcdsaFormat = false // 使用标准 RFC 5656 格式

        fun setLegacyEcdsaFormat(enabled: Boolean) {
            useLegacyEcdsaFormat = enabled
        }

        fun buildEcdsaPublicKeyBlob(publicKey: ECPublicKey): ByteArray {
            val keyBits = publicKey.w.affineX.bitLength()
            val coordSize = if (keyBits > ECDSA_P384_MIN_BITS) 48 else 32
            val algName = if (coordSize == 48) "ecdsa-sha2-nistp384" else "ecdsa-sha2-nistp256"
            val curveName = if (coordSize == 48) "nistp384" else "nistp256"

            val x =
                publicKey.w.affineX
                    .toByteArray()
                    .let { trimLeadingZero(it, coordSize) }
            val y =
                publicKey.w.affineY
                    .toByteArray()
                    .let { trimLeadingZero(it, coordSize) }

            val buf = ByteArrayOutputStream()
            writeString(buf, algName.toByteArray())

            if (useLegacyEcdsaFormat) {
                val point = ByteArray(coordSize * 2)
                System.arraycopy(x, 0, point, 0, coordSize)
                System.arraycopy(y, 0, point, coordSize, coordSize)
                writeString(buf, point)
            } else {
                writeString(buf, curveName.toByteArray())
                val point = ByteArray(1 + coordSize * 2)
                point[0] = 0x04
                System.arraycopy(x, 0, point, 1, coordSize)
                System.arraycopy(y, 0, point, 1 + coordSize, coordSize)
                writeString(buf, point)
            }
            return buf.toByteArray()
        }

        fun buildRsaPublicKeyBlob(publicKey: java.security.interfaces.RSAPublicKey): ByteArray {
            val buf = ByteArrayOutputStream()
            writeString(buf, "ssh-rsa".toByteArray())
            writeString(buf, publicKey.publicExponent.toByteArray())
            writeString(buf, publicKey.modulus.toByteArray())
            return buf.toByteArray()
        }

        fun buildEd25519PublicKeyBlob(publicKey: java.security.Key): ByteArray {
            // Ed25519 公钥: [string 'ssh-ed25519'] [string 32-byte key]
            val encoded = publicKey.encoded
            // X.509 SubjectPublicKeyInfo 格式包含算法标识和公钥字节
            // 提取最后 32 字节作为 SSH 公钥
            val ed25519KeyBytes =
                if (encoded.size >= 44) {
                    // 标准 X.509 格式: 30 2a 30 05 06 03 2b 65 70 03 21 00 [32 bytes key]
                    encoded.takeLast(32).toByteArray()
                } else {
                    // 尝试直接使用
                    encoded
                }

            val buf = ByteArrayOutputStream()
            writeString(buf, "ssh-ed25519".toByteArray())
            writeString(buf, ed25519KeyBytes)
            return buf.toByteArray()
        }

        internal fun trimLeadingZero(
            bytes: ByteArray,
            targetSize: Int,
        ): ByteArray {
            val trimmed =
                if (bytes.size > targetSize && bytes[0].toInt() == 0) {
                    bytes.copyOfRange(1, bytes.size)
                } else {
                    bytes
                }
            if (trimmed.size == targetSize) return trimmed
            val result = ByteArray(targetSize)
            val offset = targetSize - trimmed.size
            System.arraycopy(trimmed, 0, result, offset, trimmed.size)
            return result
        }

        internal fun writeString(
            out: ByteArrayOutputStream,
            data: ByteArray,
        ) {
            val len = data.size
            out.write(len shr 24 and 0xFF)
            out.write(len shr 16 and 0xFF)
            out.write(len shr 8 and 0xFF)
            out.write(len and 0xFF)
            out.write(data)
        }
    }
}

/** Curve bit length at/above which the P-384 parameters (and SHA-384) apply. */
internal const val ECDSA_P384_MIN_BITS = 320

/**
 * Digest/JCA algorithm for a given EC curve size. Must match [AndroidKeyStoreIdentity.getAlgName]
 * so that a key declared `ecdsa-sha2-nistp384` is actually signed with SHA-384.
 */
internal fun ecdsaDigestForCurveSize(bits: Int): String =
    when {
        bits > ECDSA_P384_MIN_BITS -> "SHA384withECDSA"
        else -> "SHA256withECDSA"
    }

/** JCA digest for the negotiated RSA signature algorithm. */
internal fun rsaSignAlg(alg: String): String =
    when (alg) {
        "rsa-sha2-512" -> "SHA512withRSA"
        "rsa-sha2-256" -> "SHA256withRSA"
        else -> "SHA1withRSA"
    }

/**
 * Standard SSH signature blob: `string alg ‖ string rawSignature`.
 * [rawSig] is the raw signature bytes (PKCS#1 v1.5 for RSA, 64 raw bytes for Ed25519).
 */
internal fun buildRawSignatureBlob(
    alg: String,
    rawSig: ByteArray,
): ByteArray {
    val buf = ByteArrayOutputStream()
    AndroidKeyStoreIdentity.writeString(buf, alg.toByteArray())
    AndroidKeyStoreIdentity.writeString(buf, rawSig)
    return buf.toByteArray()
}

/**
 * ECDSA SSH signature blob: `string alg ‖ string (mpint r ‖ mpint s)`.
 * [derSig] is the DER-encoded signature produced by JCA (`SHAxxxwithECDSA`).
 */
internal fun buildEcdsaSignatureBlob(
    alg: String,
    derSig: ByteArray,
): ByteArray {
    val (r, s) = parseDerSignature(derSig)

    // Inner: mpint r + mpint s
    val inner = ByteArrayOutputStream()
    writeMpInt(inner, r)
    writeMpInt(inner, s)

    return buildRawSignatureBlob(alg, inner.toByteArray())
}

/**
 * Ed25519 SSH signature blob: `string alg ‖ string <64-byte raw signature>`.
 *
 * Android Keystore `Signature.getInstance("Ed25519")` returns the raw 64-byte signature, but
 * some providers emit a DER-encoded one; normalize to raw 64 bytes when needed.
 */
internal fun buildEd25519SignatureBlob(
    alg: String,
    sigBytes: ByteArray,
): ByteArray {
    val rawSig = if (sigBytes.size == 64) sigBytes else parseEd25519DerSignature(sigBytes)
    return buildRawSignatureBlob(alg, rawSig)
}

/** Parse a DER `SEQUENCE { INTEGER r, INTEGER s }` signature (multi-byte length aware). */
internal fun parseDerSignature(derSig: ByteArray): Pair<ByteArray, ByteArray> {
    var pos = 0
    require(derSig[pos++].toInt() == 0x30) { "bad DER" }
    // 跳过 SEQUENCE 长度（支持多字节编码）
    val firstLenByte = derSig[pos++].toInt() and 0xFF
    if (firstLenByte and 0x80 != 0) {
        val numLenBytes = firstLenByte and 0x7F
        pos += numLenBytes
    }

    require(derSig[pos++].toInt() == 0x02) { "bad r tag" }
    val rLen = derSig[pos++].toInt() and 0xFF
    val r = derSig.copyOfRange(pos, pos + rLen)
    pos += rLen

    require(derSig[pos++].toInt() == 0x02) { "bad s tag" }
    val sLen = derSig[pos++].toInt() and 0xFF
    val s = derSig.copyOfRange(pos, pos + sLen)

    return r to s
}

/**
 * Parse the DER-encoded Ed25519 signature
 * `30 42 80 20 [32 bytes r] 81 20 [32 bytes s]` and return raw `r ‖ s` (64 bytes).
 */
internal fun parseEd25519DerSignature(derSig: ByteArray): ByteArray {
    var pos = 0
    require(derSig[pos++].toInt() == 0x30) { "bad DER SEQUENCE" }
    // 跳过 SEQUENCE 长度（支持多字节编码）
    val firstLenByte = derSig[pos++].toInt() and 0xFF
    if (firstLenByte and 0x80 != 0) {
        val numLenBytes = firstLenByte and 0x7F
        pos += numLenBytes
    }

    require(derSig[pos++].toInt() == 0x02) { "bad INTEGER tag for r" }
    val rLen = derSig[pos++].toInt() and 0xFF
    val r = derSig.copyOfRange(pos, pos + rLen)
    pos += rLen

    require(derSig[pos++].toInt() == 0x02) { "bad INTEGER tag for s" }
    val sLen = derSig[pos++].toInt() and 0xFF
    val s = derSig.copyOfRange(pos, pos + sLen)

    // Ed25519 r and s are each 32 bytes, pad if needed
    val rPadded = ByteArray(32)
    val sPadded = ByteArray(32)
    System.arraycopy(r, r.size - minOf(r.size, 32), rPadded, 32 - minOf(r.size, 32), minOf(r.size, 32))
    System.arraycopy(s, s.size - minOf(s.size, 32), sPadded, 32 - minOf(s.size, 32), minOf(s.size, 32))

    return rPadded + sPadded
}

/** Encode an SSH `mpint` (two's-complement, minimal, sign-prefixed) as a length-prefixed string. */
internal fun writeMpInt(
    out: ByteArrayOutputStream,
    value: ByteArray,
) {
    // Strip leading zeros
    var start = 0
    while (start < value.size - 1 && value[start].toInt() == 0) start++
    val trimmed = value.copyOfRange(start, value.size)

    // Add leading zero if high bit set (mpint sign requirement)
    val mpInt =
        if ((trimmed[0].toInt() and 0x80) != 0) {
            ByteArray(trimmed.size + 1).also { System.arraycopy(trimmed, 0, it, 1, trimmed.size) }
        } else {
            trimmed
        }

    AndroidKeyStoreIdentity.writeString(out, mpInt)
}
