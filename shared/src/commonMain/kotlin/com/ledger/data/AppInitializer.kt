package com.ledger.data

import com.ledger.data.seed.DefaultSeed
import com.ledger.domain.port.ClockPort
import com.ledger.domain.repository.AccountRepository
import com.ledger.domain.repository.CategoryRepository

/**
 * 应用启动引导（PRD §11.2「预置大学生默认分类开箱即用」）。
 *
 * 幂等，可重复调用：
 *  - 分类：按 `(kind, name)` 增量补齐，不覆盖用户改过的图标/颜色/排序；
 *  - 账户：仅在账户表为空时写入预置「现金」账户（见 [DefaultSeed] 关于此项的说明）。
 *
 * 放在 `data` 层而不是 `domain`：它依赖「表是否为空」这类存储细节。
 * 调用时机：UI 启动协程里 await，或直接在 Koin 启动时用 `createdAtStart` 触发。
 */
class AppInitializer(
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val clock: ClockPort,
) {

    suspend fun initialize() {
        categoryRepository.seedIfEmpty(DefaultSeed.defaultCategories())

        if (accountRepository.count() == 0L) {
            val now = clock.nowUtcMillis()
            DefaultSeed.defaultAccounts(now).forEach { accountRepository.insert(it) }
        }
    }
}
