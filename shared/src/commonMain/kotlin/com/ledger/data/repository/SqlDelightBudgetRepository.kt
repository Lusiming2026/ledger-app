package com.ledger.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.ledger.data.db.LedgerDatabase
import com.ledger.data.mapper.toDomain
import com.ledger.domain.model.Budget
import com.ledger.domain.model.YearMonth
import com.ledger.domain.repository.BudgetRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [BudgetRepository] 的 SQLDelight 实现（T08 / T20）。
 *
 * `uq_budget_period` 保证每自然月最多一条总预算（PRD §7.3）。
 */
class SqlDelightBudgetRepository(
    private val database: LedgerDatabase,
    private val dispatcher: CoroutineDispatcher,
) : BudgetRepository {

    private val queries = database.budgetQueries

    override fun observeByPeriod(yearMonth: YearMonth): Flow<Budget?> =
        queries.selectByPeriod(year = yearMonth.year.toLong(), month = yearMonth.month.toLong())
            .asFlow()
            .mapToOneOrNull(dispatcher)
            .map { it?.toDomain() }

    override suspend fun getByPeriod(yearMonth: YearMonth): Budget? = withContext(dispatcher) {
        queries.selectByPeriod(year = yearMonth.year.toLong(), month = yearMonth.month.toLong())
            .executeAsOneOrNull()
            ?.toDomain()
    }

    override suspend fun getById(budgetId: String): Budget? = withContext(dispatcher) {
        queries.selectById(budgetId = budgetId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getAll(): List<Budget> = withContext(dispatcher) {
        queries.selectAll().executeAsList().map { it.toDomain() }
    }

    override suspend fun upsert(budget: Budget): Budget = withContext(dispatcher) {
        database.transactionWithResult {
            val existing = queries
                .selectByPeriod(year = budget.year.toLong(), month = budget.month.toLong())
                .executeAsOneOrNull()

            if (existing == null) {
                queries.insertBudget(
                    budgetId = budget.budgetId,
                    amount = budget.amount,
                    year = budget.year.toLong(),
                    month = budget.month.toLong(),
                    createdAt = budget.createdAt,
                    updatedAt = budget.updatedAt,
                )
                budget
            } else {
                // 复用既有 budget_id，保住 budget_notification 的幂等状态（PRD §7.3）
                queries.updateBudgetAmount(
                    amount = budget.amount,
                    updatedAt = budget.updatedAt,
                    budgetId = existing.budget_id,
                )
                existing.copy(amount = budget.amount, updated_at = budget.updatedAt).toDomain()
            }
        }
    }

    override suspend fun updateAmount(budgetId: String, amountCents: Long, updatedAt: Long) =
        withContext(dispatcher) {
            // 不重置已产生的通知记录（PRD §7.3）
            queries.updateBudgetAmount(
                amount = amountCents,
                updatedAt = updatedAt,
                budgetId = budgetId,
            )
        }

    override suspend fun delete(budgetId: String) = withContext(dispatcher) {
        database.transaction {
            // ON DELETE CASCADE 依赖 PRAGMA foreign_keys，默认未开启 → 应用层显式删除
            database.budgetNotificationQueries.deleteByBudget(budgetId = budgetId)
            queries.deleteBudget(budgetId = budgetId)
        }
    }

    override suspend fun insertAllRaw(budgets: List<Budget>) = withContext(dispatcher) {
        database.transaction {
            budgets.forEach { b ->
                queries.insertBudget(
                    budgetId = b.budgetId,
                    amount = b.amount,
                    year = b.year.toLong(),
                    month = b.month.toLong(),
                    createdAt = b.createdAt,
                    updatedAt = b.updatedAt,
                )
            }
        }
    }

    override suspend fun deleteAll() = withContext(dispatcher) {
        queries.deleteAllForRestore()
    }
}
