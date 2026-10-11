package com.ledger.data.db

import app.cash.sqldelight.db.SqlDriver
import com.ledger.domain.port.KeyStorePort

/** 本地 SQLCipher 数据库口令长度：256-bit（PRD §7.4）。 */
const val DB_KEY_LENGTH: Int = 32

/**
 * 平台侧 SQLCipher 驱动工厂（T07）。
 *
 * commonMain 只依赖这个最小契约，SQLCipher / SQLiter 的全部细节留在
 * `androidMain`（`net.zetetic.database.sqlcipher.SupportOpenHelperFactory`）与
 * `iosMain`（`co.touchlab.sqliter.DatabaseConfiguration.Encryption`）里，
 * 满足 PRD §8「domain 不依赖 SQLCipher」的约束。
 *
 * 声明为 `fun interface`，平台模块可用 lambda 直接适配既有工厂类：
 * ```kotlin
 * single<SqlDriverFactory> { SqlDriverFactory { key -> get<AndroidDriverFactory>().create(key) } }
 * ```
 */
fun interface SqlDriverFactory {
    /**
     * 以 [dbKey] 打开（不存在则创建）加密数据库文件。
     * 库文件固定为 `ledger.db`，位于应用私有目录。
     */
    fun create(dbKey: ByteArray): SqlDriver
}

/**
 * DB Key 的取得与首次生成（T09）。
 *
 * 只在 Keychain / Keystore 中存一份，**绝不落库、绝不出现在备份文件里**（PRD §7.4）。
 * 首次运行生成 32 字节高熵随机密钥；此后读回同一把。
 *
 * 若读回的密钥长度异常（例如历史脏数据），重新生成会造成旧库无法解密，
 * 因此此处保守处理：长度不符时仍按原值使用，交由驱动报错，避免静默毁库。
 */
class DatabaseKeyProvider(
    private val keyStore: KeyStorePort,
) {

    fun ensureDbKey(): ByteArray {
        // 接口语义（见 KeyStorePort.getDbKey）：非空，首次调用即生成 32 字节并持久化。
        // 因此这里直接取用即可，无需判空；长度异常时保守沿用原值（见类注释）。
        return keyStore.getDbKey()
    }
}

/**
 * 创建 `LedgerDatabase` 实例。
 *
 * 建表 / 升级由 SQLDelight 的 `Schema` 在驱动内部完成（schemaVersion 由 `.sq` 派生），
 * 因此这里不需要手写 `Schema.create`。
 */
class LedgerDatabaseFactory(
    private val driverFactory: SqlDriverFactory,
    private val keyProvider: DatabaseKeyProvider,
) {

    fun create(): LedgerDatabase {
        val driver = driverFactory.create(keyProvider.ensureDbKey())
        return LedgerDatabase(driver)
    }
}
