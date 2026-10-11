package com.ledger.data.repository

import com.ledger.data.db.LedgerDatabase
import com.ledger.data.mapper.toDomain
import com.ledger.domain.model.BudgetNotification
import com.ledger.domain.repository.BudgetNotificationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [BudgetNotificationRepository] 的 SQLDelight 实现（T21）。
 *
 * 这里是「每周期每阈值最多通知一次」的**权威幂等判定源**（PRD §7.3 / §11.4），
 * 背后是唯一索引 `uq_notify_once(budget_id, threshold, period_key)`。
 *
 * 并发安全：判重与写入放在同一事务里，唯一索引作为最后一道兜底，
 * 因此即便两个协程同时到达阈值，也只有一条会真正写入。
 */
class SqlDelightBudgetNotificationRepository(
    private val database: LedgerDatabase,
    private val dispatcher: CoroutineDispatcher,
) : BudgetNotificationRepository {

    private val queries = database.budgetNotificationQueries

    override suspend fun exists(budgetId: String, threshold: Int, periodKey: String): Boolean =
        withContext(dispatcher) {
            queries.existsNotification(
                budgetId = budgetId,
                threshold = threshold.toLong(),
                periodKey = periodKey,
            ).executeAsOne() > 0L
        }

    override suspend fun getByBudget(budgetId: String): List<BudgetNotification> =
        withContext(dispatcher) {
            queries.selectByBudget(budgetId = budgetId).executeAsList().map { it.toDomain() }
        }

    override suspend fun getAll(): List<BudgetNotification> = withContext(dispatcher) {
        queries.selectAll().executeAsList().map { it.toDomain() }
    }

    override suspend fun insertIfAbsent(notification: BudgetNotification): Boolean =
        withContext(dispatcher) {
            database.transactionWithResult {
                val alreadyNotified = queries.existsNotification(
                    budgetId = notification.budgetId,
                    threshold = notification.threshold.toLong(),
                    periodKey = notification.periodKey,
                ).executeAsOne() > 0L

                if (alreadyNotified) return@transactionWithResult false

                queries.insertNotification(
                    notificationId = notification.notificationId,
                    budgetId = notification.budgetId,
                    threshold = notification.threshold.toLong(),
                    periodKey = notification.periodKey,
                    notifiedAt = notification.notifiedAt,
                )
                true
            }
        }

    override suspend fun deleteByBudget(budgetId: String) = withContext(dispatcher) {
        queries.deleteByBudget(budgetId = budgetId)
    }

    override suspend fun insertAllRaw(notifications: List<BudgetNotification>) =
        withContext(dispatcher) {
            database.transaction {
                notifications.forEach { n ->
                    queries.insertNotification(
                        notificationId = n.notificationId,
                        budgetId = n.budgetId,
                        threshold = n.threshold.toLong(),
                        periodKey = n.periodKey,
                        notifiedAt = n.notifiedAt,
                    )
                }
            }
        }

    override suspend fun deleteAll() = withContext(dispatcher) {
        queries.deleteAllForRestore()
    }
}
