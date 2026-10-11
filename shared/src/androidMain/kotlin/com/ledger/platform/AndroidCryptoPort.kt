package com.ledger.platform

import com.ledger.domain.error.CryptoError
import com.ledger.domain.port.AesGcmResult
import com.ledger.domain.port.CryptoPort
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * [CryptoPort] 的 Android 实现（T24/T25）。
 *
 * 关键收益：PBKDF2-HMAC-SHA256 走 `SecretKeyFactory("PBKDF2WithHmacSHA256")`，
 * 这是 `Conscrypt`/BoringSSL 的原生实现，600,000 次迭代在目标机型上能落进
 * PRD §7.4 要求的 **200–500ms**；纯 Kotlin 实现会差一个数量级。
 *
 * AES-256-GCM 走 `javax.crypto.Cipher("AES/GCM/NoPadding")`，
 * 注意 JCE 的 GCM 输出把 tag 拼在密文尾部，这里拆成 `ciphertext + tag` 两段，
 * 以贴合备份格式 v1 的字段布局（PRD §7.5）。
 */
class AndroidCryptoPort : CryptoPort {

    private val random = SecureRandom()

    override fun randomBytes(size: Int): ByteArray {
        if (size <= 0) throw CryptoError.RandomUnavailable
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    override fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLenBytes: Int,
    ): ByteArray {
        val chars = password.map { (it.toInt() and 0xFF).toChar() }.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, dkLenBytes * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (e: Throwable) {
            throw CryptoError.EncryptionFailed
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    override fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
    ): AesGcmResult {
        require(key.size == AES_KEY_LENGTH) { "AES-256 需要 32 字节密钥" }
        require(nonce.size == GCM_NONCE_LENGTH) { "GCM nonce 需要 12 字节" }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            val combined = cipher.doFinal(plaintext)
            // JCE 把 tag 附在末尾，拆成两段以匹配备份格式 v1
            val bodyLength = combined.size - GCM_TAG_LENGTH
            AesGcmResult(
                ciphertext = combined.copyOfRange(0, bodyLength),
                tag = combined.copyOfRange(bodyLength, combined.size),
            )
        } catch (e: Throwable) {
            throw CryptoError.EncryptionFailed
        }
    }

    override fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
    ): ByteArray? {
        if (key.size != AES_KEY_LENGTH ||
            nonce.size != GCM_NONCE_LENGTH ||
            tag.size != GCM_TAG_LENGTH
        ) {
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            // 还原成 JCE 期望的「密文 + tag」布局
            val combined = ciphertext + tag
            cipher.doFinal(combined)
        } catch (e: Throwable) {
            // AEADBadTagException = 主密码错误或文件被篡改 → 返回 null 由上层转成 WrongPassword
            null
        }
    }

    override fun zeroize(bytes: ByteArray) {
        bytes.fill(0)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_LENGTH = 32
        const val GCM_NONCE_LENGTH = 12
        const val GCM_TAG_LENGTH = 16
        const val GCM_TAG_BITS = GCM_TAG_LENGTH * 8
    }
}
