package com.ledger.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.ledger.data.db.LedgerDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher 驱动的 Android 工厂：用 SupportOpenHelperFactory 以 raw key（256-bit）
 * 打开加密 SQLite。SQLCipher 只出现在本文件，commonMain 零接触。
 *
 * ### 依赖构件说明（易踩坑）
 * `net.zetetic.database.sqlcipher.SupportOpenHelperFactory` 来自**新版构件**
 * `net.zetetic:sqlcipher-android`（4.5.4+）。
 * 旧构件 `net.zetetic:android-database-sqlcipher`（止于 4.5.4）的包名是
 * `net.sqlcipher.database`、工厂类叫 `SupportFactory`——两者**不能混用**，
 * 混用会直接编译不过。本工程的 `gradle/libs.versions.toml` 已固定为前者。
 *
 * ### 使用前置条件
 * 必须在使用前加载 native 库，否则会抛 `UnsatisfiedLinkError`：
 * 在 `Application.onCreate()` 里调用 `System.loadLibrary("sqlcipher")`，
 * 本工程的 `LedgerApplication` 已经做了。
 */
class AndroidDriverFactory(private val context: Context) {

    fun create(dbKey: ByteArray): SqlDriver =
        AndroidSqliteDriver(
            schema = LedgerDatabase.Schema,
            context = context,
            name = "ledger.db",
            factory = SupportOpenHelperFactory(dbKey),
        )
}
