package com.ledger.android

import android.app.Application
import com.ledger.data.AppInitializer
import com.ledger.di.doInitKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.koin.core.Koin

/**
 * 应用入口（T03）。
 *
 * 这里只做三件事，顺序不能变：
 *  1. `System.loadLibrary("sqlcipher")` —— 必须在任何 SQLCipher 调用之前，
 *     否则 `AndroidDriverFactory` 打开数据库时会抛 `UnsatisfiedLinkError`；
 *  2. [doInitKoin] —— 装配依赖图（commonModule + androidPlatformModule）；
 *  3. 启动引导 [AppInitializer.initialize] —— 幂等补齐预置分类 / 预置账户。
 *
 * 第 3 步放在后台协程，不阻塞 `onCreate`（PRD §11.7 冷启动 P50 < 1s）。
 * 但**不能放任它自己跑**：数据库首次打开要跑密钥派生 + SQLCipher 解锁，
 * 首页若要立刻查数据就会撞上「表还没建」。因此把任务存成 [startup]，
 * 由 P01 启动页 await —— 把「冷启动」和「首屏可用」串成一条确定时序，
 * 而不是靠 `delay()` 或重试。
 */
class LedgerApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var koin: Koin
        private set

    /** 启动引导任务；失败时 `await()` 会抛出，由启动页捕获并展示原因。 */
    lateinit var startup: Deferred<Unit>
        private set

    /**
     * SQLCipher native 库加载失败的原因（极少数不支持的 ABI 会走到这里）。
     * 不为 null 时，启动页展示明确提示而不是让进程直接崩掉。
     */
    var nativeLoadError: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()

        nativeLoadError = runCatching { System.loadLibrary("sqlcipher") }.exceptionOrNull()

        koin = doInitKoin(this)

        startup = appScope.async {
            nativeLoadError?.let { throw it }
            koin.get<AppInitializer>().initialize()
        }
    }

    companion object {
        /** T27 桌面快捷方式的动作；`MainActivity` 据此决定冷启动后是否直接进记账页。 */
        const val ACTION_RECORD: String = "com.ledger.android.action.RECORD"
    }
}
