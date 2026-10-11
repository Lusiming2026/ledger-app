package com.ledger.platform

import android.content.Context
import com.ledger.domain.port.PreferencePort
import com.ledger.domain.port.PreferenceKeys

/**
 * [PreferencePort] 的 Android 实现（PRD §11.1 上次值记忆 / §11.7 深色模式）。
 *
 * 用独立的 `SharedPreferences` 文件（`ledger_ui_prefs`），**不复用** `AndroidKeyStorePort`
 * 的 `EncryptedSharedPreferences`：这里存的是「上次选了哪个分类」和「是不是深色」，
 * 既不是秘密、又要读得尽可能快（首帧就要用），套一层 Keystore 解密只会拖慢冷启动。
 * 真正的秘密（DB Key）仍在 Keystore 里。
 *
 * `LedgerApplication` 里声明了 `allowBackup=false` + 排除全部备份域，
 * 所以这些偏好不会被系统云备份带走——符合 PRD「全部数据本地存储」。
 */
class AndroidPreferencePort(context: Context) : PreferencePort {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        // apply() 而不是 commit()：偏好是「尽力而为」的，不值得阻塞调用线程。
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    /** 供设置页「清空界面偏好」用；不影响账本数据。 */
    fun clearAll() {
        // 逐个保留键名是为了让调用方看得见清掉的是什么；这里直接全清该文件即可。
        prefKeys().forEach { prefs.edit().remove(it).apply() }
    }

    private fun prefKeys(): List<String> = listOf(
        PreferenceKeys.THEME_MODE,
        PreferenceKeys.LAST_TRANSACTION_TYPE,
        PreferenceKeys.LAST_ACCOUNT_ID,
        PreferenceKeys.LAST_FROM_ACCOUNT_ID,
        PreferenceKeys.LAST_TO_ACCOUNT_ID,
        PreferenceKeys.MASTER_PASSWORD_PROMPTED,
    ) + listOf("expense", "income").map { PreferenceKeys.lastCategoryId(it) }

    private companion object {
        const val PREF_FILE = "ledger_ui_prefs"
    }
}
