package com.ledger.di

import android.content.Context
import com.ledger.data.db.SqlDriverFactory
import com.ledger.db.AndroidDriverFactory
import com.ledger.domain.port.CryptoPort
import com.ledger.domain.port.FilePickerPort
import com.ledger.domain.port.KeyStorePort
import com.ledger.domain.port.NotificationSchedulerPort
import com.ledger.domain.port.PreferencePort
import com.ledger.platform.AndroidCryptoPort
import com.ledger.platform.AndroidFilePickerPort
import com.ledger.platform.AndroidKeyStorePort
import com.ledger.platform.AndroidNotificationPort
import com.ledger.platform.AndroidPreferencePort
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android 侧端口实现装配（T03 / T07 / T09）。
 *
 * 这一层是 domain 与 Android 系统 API 之间**唯一**的接触面：
 * Keystore / EncryptedSharedPreferences / SAF / NotificationManager / SQLCipher
 * 都只出现在这里，`commonMain` 完全不感知。
 */
fun androidPlatformModule(context: Context): Module = module {

    single<KeyStorePort> { AndroidKeyStorePort(context) }
    single<CryptoPort> { AndroidCryptoPort() }
    single<NotificationSchedulerPort> { AndroidNotificationPort(context) }
    single<PreferencePort> { AndroidPreferencePort(context) }

    // 注意：必须是**同一个实例**——UI 层要拿到具体类型去注册 Bridge，
    // 领域层拿接口；两个 single 分别构造会得到两个互不相干的桥。
    single { AndroidFilePickerPort() }
    single<FilePickerPort> { get<AndroidFilePickerPort>() }

    single { AndroidDriverFactory(context) }
    single<SqlDriverFactory> { SqlDriverFactory { dbKey -> get<AndroidDriverFactory>().create(dbKey) } }
}

/**
 * 启动 Koin（供 `LedgerApplication.onCreate` / `MainActivity` 调用）。
 *
 * 采用「先 commonModule、后平台模块」的顺序：平台模块可以提供同名绑定的覆盖实现
 * （例如把 `DB_DISPATCHER` 换成 Android 专用线程池）。
 */
fun doInitKoin(applicationContext: Context): Koin = startKoin {
    androidContext(applicationContext)
    modules(commonModule(), androidPlatformModule(applicationContext))
}.koin
