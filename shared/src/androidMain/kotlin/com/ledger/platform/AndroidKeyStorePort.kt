package com.ledger.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ledger.domain.port.KeyStorePort

/**
 * [KeyStorePort] 的 Android 实现（T09）。
 *
 * 存储介质：`EncryptedSharedPreferences`，其主密钥（MasterKey）由 **Android Keystore**
 * 生成并保管（AES256_GCM，不可导出）。这样即使拿到应用的私有目录文件，
 * 没有 Keystore 中的硬件保护密钥也无法解出 `db_key`（PRD §7.4 / §10「安全」）。
 *
 * 存放内容（PRD §6.2.7 安全元数据）：
 *  - `db_key`：随机 256-bit，SQLCipher 整库加密口令；
 *  - `kp_salt` / `kp_verifier` / `kp_kdf_params`：主密码校验元数据（**不是主密码本身**）。
 *
 * 说明：`db_key` 以 Base64 文本存入 —— Keystore 主密钥已经提供了「静态加密」，
 * 这里不需要再做一层自研加密；Base64 只是为了让二进制能放进 String 偏好项。
 */
class AndroidKeyStorePort(context: Context) : KeyStorePort {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ---------------------------------------------------------------- db_key

    override fun getDbKey(): ByteArray {
        prefs.getString(KEY_DB_KEY, null)?.let { text ->
            decode(text)?.let { return it }
        }
        // 首次调用：生成 32 字节安全随机数并持久化（接口语义：重启后必须取到同一个 key）
        val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_DB_KEY, encode(fresh)).apply()
        return fresh
    }

    override fun setDbKey(key: ByteArray) {
        prefs.edit().putString(KEY_DB_KEY, encode(key)).apply()
    }

    // ------------------------------------------------------------------- salt

    override fun getSalt(): ByteArray? = prefs.getString(KEY_SALT, null)?.let(::decode)

    override fun setSalt(salt: ByteArray) {
        prefs.edit().putString(KEY_SALT, encode(salt)).apply()
    }

    // --------------------------------------------------------------- verifier

    override fun getVerifier(): ByteArray? = prefs.getString(KEY_VERIFIER, null)?.let(::decode)

    override fun setVerifier(verifier: ByteArray) {
        prefs.edit().putString(KEY_VERIFIER, encode(verifier)).apply()
    }

    // ------------------------------------------------------------- kdf params

    override fun getKdfParams(): String? = prefs.getString(KEY_KDF_PARAMS, null)

    override fun setKdfParams(params: String) {
        prefs.edit().putString(KEY_KDF_PARAMS, params).apply()
    }

    // ------------------------------------------------------------------ 其它

    override fun hasMasterPassword(): Boolean =
        prefs.contains(KEY_SALT) && prefs.contains(KEY_VERIFIER)

    override fun clearMasterPasswordMetadata() {
        prefs.edit()
            .remove(KEY_SALT)
            .remove(KEY_VERIFIER)
            .remove(KEY_KDF_PARAMS)
            .apply()
    }

    private fun encode(bytes: ByteArray): String = android.util.Base64.encodeToString(
        bytes,
        android.util.Base64.NO_WRAP,
    )

    private fun decode(text: String): ByteArray? = try {
        android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        // 脏数据：当作没有，让上层走「重新生成」路径而不是崩溃
        null
    }

    private companion object {
        const val MASTER_KEY_ALIAS = "ledger_master_key"
        const val PREFS_FILE_NAME = "ledger_secure_prefs"

        const val KEY_DB_KEY = "db_key"
        const val KEY_SALT = "kp_salt"
        const val KEY_VERIFIER = "kp_verifier"
        const val KEY_KDF_PARAMS = "kp_kdf_params"
    }
}
