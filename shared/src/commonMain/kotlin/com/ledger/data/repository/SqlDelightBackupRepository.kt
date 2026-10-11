package com.ledger.data.repository

import com.ledger.data.db.LedgerDatabase
import com.ledger.data.mapper.toDomain
import com.ledger.domain.model.Account
import com.ledger.domain.model.Budget
import com.ledger.domain.model.BudgetNotification
import com.ledger.domain.model.Category
import com.ledger.domain.model.Reconciliation
import com.ledger.domain.model.Transaction
import com.ledger.domain.repository.BalanceDelta
import com.ledger.domain.repository.BackupRepository
import com.ledger.domain.repository.LedgerSnapshot
import com.ledger.domain.repository.MergeReport
import com.ledger.domain.service.BalanceService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [BackupRepository] 的 SQLDelight 实现（T24 / T25，PRD §7.6）。
 *
 * ### 覆盖模式 [replaceAll]
 * 单事务内「清空 → 整体导入」。任何一步抛异常，SQLDelight 会回滚整个事务，
 * 本地数据保持原样（时序图不变式 I1/I2「失败不破坏现有数据」）。
 *
 * ### 合并模式 [mergeAll]
 * 去重键（PRD §7.6.2 第 7 步）：
 *  - `account` / `transaction` / `reconciliation`：按主键去重，本地已存在则跳过；
 *  - `category`：先按主键，再按 `(kind, name)`——同 kind 同名但 ID 不同时**保留本地**，
 *    并把导入流水中指向该分类的引用**改写到本地分类 ID**（避免出现悬空分类）；
 *  - `budget`：按 `(year, month)` 去重，同月**以导入文件为准**（改动本地金额，保留本地 budget_id，
 *    以免切断 `budget_notification` 的幂等链）；
 *  - `budget_notification`：按业务键 `(budgetId, threshold, periodKey)` 去重，
 *    并把导入的 budget_id 映射到本地实际生效的 budget_id。
 *
 * ### 合并模式的余额语义（PRD 未明确，此处显式定义并在 README 标注）
 *  - 本次**新插入**的账户：其 `current_balance` 来自快照，已经包含了该账户名下流水的效果，
 *    因此不再对它的流水做余额联动（否则重复计一次）；
 *  - 合并前**本地已存在**的账户：其流水被追加进来，因此对新插入的流水执行正向余额联动，
 *    使「余额 = 初始 + Σ流水」的不变量继续成立。
 */
class SqlDelightBackupRepository(
    private val database: LedgerDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val balanceService: BalanceService,
) : BackupRepository {

    override suspend fun snapshot(): LedgerSnapshot = withContext(dispatcher) {
        // 同一事务内读取，保证是同一个时间点的一致视图
        database.transactionWithResult {
            LedgerSnapshot(
                accounts = database.accountQueries.selectAll().executeAsList().map { it.toDomain() },
                categories = database.categoryQueries.selectAll().executeAsList().map { it.toDomain() },
                transactions = database.transactionQueries.selectActiveAll().executeAsList()
                    .map { it.toDomain() },
                budgets = database.budgetQueries.selectAll().executeAsList().map { it.toDomain() },
                budgetNotifications = database.budgetNotificationQueries.selectAll().executeAsList()
                    .map { it.toDomain() },
                reconciliations = database.reconciliationQueries.selectAll().executeAsList()
                    .map { it.toDomain() },
            )
        }
    }

    // ------------------------------------------------------------- 覆盖模式

    override suspend fun replaceAll(snapshot: LedgerSnapshot) = withContext(dispatcher) {
        database.transaction {
            clearAll()
            insertAccounts(snapshot.accounts)
            insertCategories(snapshot.categories)
            insertTransactions(snapshot.transactions)
            insertBudgets(snapshot.budgets)
            insertNotifications(snapshot.budgetNotifications)
            insertReconciliations(snapshot.reconciliations)
        }
    }

    private fun clearAll() {
        // 子表在前，父表在后（即便外键未开启，顺序也保持正确语义）
        database.transactionQueries.deleteAllForRestore()
        database.budgetNotificationQueries.deleteAllForRestore()
        database.reconciliationQueries.deleteAllForRestore()
        database.budgetQueries.deleteAllForRestore()
        database.categoryQueries.deleteAllForRestore()
        database.accountQueries.deleteAllForRestore()
    }

    // ------------------------------------------------------------- 合并模式

    override suspend fun mergeAll(snapshot: LedgerSnapshot): MergeReport = withContext(dispatcher) {
        database.transactionWithResult {
            var inserted = 0
            var skipped = 0
            val balanceMarker = nowMarker(snapshot)

            // ---- 账户：按主键去重
            val localAccountIds = database.accountQueries.selectAll()
                .executeAsList().map { it.account_id }.toMutableSet()
            val newAccountIds = mutableSetOf<String>()
            snapshot.accounts.forEach { a ->
                if (a.accountId in localAccountIds) {
                    skipped++
                } else {
                    insertAccount(a)
                    localAccountIds += a.accountId
                    newAccountIds += a.accountId
                    inserted++
                }
            }

            // ---- 分类：按主键 + (kind, name) 去重；同名保留本地，并记录 ID 改写映射
            val localCategories = database.categoryQueries.selectAll().executeAsList()
            val localCategoryIds = localCategories.map { it.category_id }.toMutableSet()
            val localCategoryByKey = localCategories
                .associateBy { key(it.kind, it.name) }
                .toMutableMap()
            val categoryIdRemap = mutableMapOf<String, String>()

            snapshot.categories.forEach { c ->
                val key = key(c.kind.dbValue, c.name)
                val localSameKey = localCategoryByKey[key]
                when {
                    c.categoryId in localCategoryIds -> {
                        skipped++
                    }

                    localSameKey != null -> {
                        // 同 kind 同名但 ID 不同：保留本地，导入流水改写到本地分类
                        categoryIdRemap[c.categoryId] = localSameKey.category_id
                        skipped++
                    }

                    else -> {
                        insertCategory(c)
                        localCategoryIds += c.categoryId
                        localCategoryByKey[key] = database.categoryQueries
                            .selectById(categoryId = c.categoryId)
                            .executeAsOne()
                        inserted++
                    }
                }
            }

            // ---- 流水：按主键去重；对「本地已存在的账户」补做余额联动
            val localTransactionIds = database.transactionQueries.selectActiveAll()
                .executeAsList().map { it.transaction_id }.toMutableSet()
            val deltasToApply = mutableListOf<BalanceDelta>()

            snapshot.transactions.forEach { t ->
                if (t.transactionId in localTransactionIds) {
                    skipped++
                    return@forEach
                }
                val remapped = t.copy(
                    categoryId = t.categoryId?.let { categoryIdRemap[it] ?: it },
                )
                insertTransaction(remapped)
                localTransactionIds += remapped.transactionId
                inserted++

                // 只对「合并前本地已存在」的账户补联动，避免新账户重复计数
                balanceService.applyDeltas(remapped)
                    .filter { it.accountId !in newAccountIds }
                    .forEach { deltasToApply += it }
            }

            // ---- 预算：按 (year, month) 去重，同月以导入为准；记录 budget_id 改写映射
            val localBudgets = database.budgetQueries.selectAll().executeAsList()
            val localBudgetIds = localBudgets.map { it.budget_id }.toMutableSet()
            val localBudgetByPeriod = localBudgets
                .associateBy { period(it.year, it.month) }
                .toMutableMap()
            val budgetIdRemap = mutableMapOf<String, String>()

            snapshot.budgets.forEach { b ->
                val localSamePeriod = localBudgetByPeriod[period(b.year.toLong(), b.month.toLong())]
                when {
                    b.budgetId in localBudgetIds -> {
                        skipped++
                    }

                    localSamePeriod != null -> {
                        // 同月以导入为准：改本地金额，保留本地 budget_id 与 created_at
                        database.budgetQueries.updateBudgetAmount(
                            amount = b.amount,
                            updatedAt = b.updatedAt,
                            budgetId = localSamePeriod.budget_id,
                        )
                        budgetIdRemap[b.budgetId] = localSamePeriod.budget_id
                        localBudgetByPeriod[period(b.year.toLong(), b.month.toLong())] =
                            localSamePeriod.copy(amount = b.amount, updated_at = b.updatedAt)
                        inserted++
                    }

                    else -> {
                        insertBudget(b)
                        localBudgetIds += b.budgetId
                        localBudgetByPeriod[period(b.year.toLong(), b.month.toLong())] =
                            database.budgetQueries.selectById(budgetId = b.budgetId).executeAsOne()
                        inserted++
                    }
                }
            }

            // ---- 预算通知：按业务键 (budgetId, threshold, periodKey) 去重
            snapshot.budgetNotifications.forEach { n ->
                val effectiveBudgetId = budgetIdRemap[n.budgetId] ?: n.budgetId
                val alreadyNotified = database.budgetNotificationQueries
                    .existsNotification(
                        budgetId = effectiveBudgetId,
                        threshold = n.threshold.toLong(),
                        periodKey = n.periodKey,
                    )
                    .executeAsOne() > 0L

                if (alreadyNotified) {
                    skipped++
                } else {
                    insertNotification(n.copy(budgetId = effectiveBudgetId))
                    inserted++
                }
            }

            // ---- 对账记录：按主键去重
            val localReconIds = database.reconciliationQueries.selectAll()
                .executeAsList().map { it.reconciliation_id }.toMutableSet()
            snapshot.reconciliations.forEach { r ->
                if (r.reconciliationId in localReconIds) {
                    skipped++
                } else {
                    insertReconciliation(r)
                    localReconIds += r.reconciliationId
                    inserted++
                }
            }

            // ---- 余额联动：最后一次性施加（同一事务内，仍满足原子性）
            deltasToApply.forEach { d ->
                database.accountQueries.addToCurrentBalance(
                    deltaCents = d.deltaCents,
                    updatedAt = balanceMarker,
                    accountId = d.accountId,
                )
            }

            MergeReport(inserted = inserted, skipped = skipped)
        }
    }

    /**
     * 合并时给账户行打一个 `updated_at`。
     * 合并语义没有单一「操作时间」，取快照中最大的 `updated_at` 作为标记，
     * 保证单调不回退（避免把账户行的 updated_at 改到过去）。
     */
    private fun nowMarker(snapshot: LedgerSnapshot): Long {
        val candidates = buildList {
            addAll(snapshot.accounts.map { it.updatedAt })
            addAll(snapshot.transactions.map { it.updatedAt })
        }
        return candidates.maxOrNull() ?: 0L
    }

    private fun key(kind: String, name: String): String = "$kind\u0000$name"

    private fun period(year: Long, month: Long): String = "$year-$month"

    // ------------------------------------------------------------- 单行插入

    private fun insertAccount(a: Account) = database.accountQueries.insertAccount(
        accountId = a.accountId,
        name = a.name,
        type = a.type.dbValue,
        initialBalance = a.initialBalance,
        currentBalance = a.currentBalance,
        sortOrder = a.sortOrder.toLong(),
        isArchived = if (a.isArchived) 1L else 0L,
        createdAt = a.createdAt,
        updatedAt = a.updatedAt,
    )

    private fun insertAccounts(list: List<Account>) = list.forEach { insertAccount(it) }

    private fun insertCategory(c: Category) = database.categoryQueries.insertCategory(
        categoryId = c.categoryId,
        name = c.name,
        kind = c.kind.dbValue,
        parentId = c.parentId,
        icon = c.icon,
        color = c.color,
        isSystem = if (c.isSystem) 1L else 0L,
        sortOrder = c.sortOrder.toLong(),
        isArchived = if (c.isArchived) 1L else 0L,
    )

    private fun insertCategories(list: List<Category>) = list.forEach { insertCategory(it) }

    private fun insertTransaction(t: Transaction) = database.transactionQueries.insertTransactionRaw(
        transactionId = t.transactionId,
        type = t.type.dbValue,
        amount = t.amount,
        categoryId = t.categoryId,
        fromAccountId = t.fromAccountId,
        toAccountId = t.toAccountId,
        note = t.note,
        occurredAt = t.occurredAt,
        createdAt = t.createdAt,
        updatedAt = t.updatedAt,
        deletedAt = t.deletedAt,
    )

    private fun insertTransactions(list: List<Transaction>) = list.forEach { insertTransaction(it) }

    private fun insertBudget(b: Budget) = database.budgetQueries.insertBudget(
        budgetId = b.budgetId,
        amount = b.amount,
        year = b.year.toLong(),
        month = b.month.toLong(),
        createdAt = b.createdAt,
        updatedAt = b.updatedAt,
    )

    private fun insertBudgets(list: List<Budget>) = list.forEach { insertBudget(it) }

    private fun insertNotification(n: BudgetNotification) =
        database.budgetNotificationQueries.insertNotification(
            notificationId = n.notificationId,
            budgetId = n.budgetId,
            threshold = n.threshold.toLong(),
            periodKey = n.periodKey,
            notifiedAt = n.notifiedAt,
        )

    private fun insertNotifications(list: List<BudgetNotification>) =
        list.forEach { insertNotification(it) }

    private fun insertReconciliation(r: Reconciliation) =
        database.reconciliationQueries.insertReconciliation(
            reconciliationId = r.reconciliationId,
            accountId = r.accountId,
            balance = r.balance,
            reconciledAt = r.reconciledAt,
            note = r.note,
        )

    private fun insertReconciliations(list: List<Reconciliation>) =
        list.forEach { insertReconciliation(it) }
}
