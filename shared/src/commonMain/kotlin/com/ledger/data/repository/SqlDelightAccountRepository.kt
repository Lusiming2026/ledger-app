package com.ledger.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ledger.data.db.LedgerDatabase
import com.ledger.data.mapper.toDomain
import com.ledger.domain.model.Account
import com.ledger.domain.model.AccountType
import com.ledger.domain.repository.AccountRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [AccountRepository] 的 SQLDelight 实现（T08）。
 *
 * 所有阻塞式 SQLDelight 调用都切到 [dispatcher]。每个写方法自身即为一个完整事务，
 * 不存在跨 `withContext` 的长事务——SQLDelight 原生驱动的事务绑定在创建它的线程上
 * （`NativeSqliteDriver` 用 ThreadLocal 持有借用连接），跨挂起点会踩坏连接归属。
 */
class SqlDelightAccountRepository(
    private val database: LedgerDatabase,
    private val dispatcher: CoroutineDispatcher,
) : AccountRepository {

    private val queries = database.accountQueries

    override fun observeAll(): Flow<List<Account>> =
        queries.selectAll()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeActive(): Flow<List<Account>> =
        queries.selectActive()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<Account> = withContext(dispatcher) {
        queries.selectAll().executeAsList().map { it.toDomain() }
    }

    override suspend fun getActive(): List<Account> = withContext(dispatcher) {
        queries.selectActive().executeAsList().map { it.toDomain() }
    }

    override suspend fun getById(accountId: String): Account? = withContext(dispatcher) {
        queries.selectById(accountId = accountId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun insert(account: Account) = withContext(dispatcher) {
        queries.insertAccount(
            accountId = account.accountId,
            name = account.name,
            type = account.type.dbValue,
            initialBalance = account.initialBalance,
            currentBalance = account.currentBalance,
            sortOrder = account.sortOrder.toLong(),
            isArchived = if (account.isArchived) 1L else 0L,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
        )
    }

    override suspend fun updateProfile(
        accountId: String,
        name: String,
        type: AccountType,
        sortOrder: Int,
        isArchived: Boolean,
        updatedAt: Long,
    ) = withContext(dispatcher) {
        // initial_balance / current_balance 不在 SET 列表内（PRD §11.3）
        queries.updateAccountProfile(
            name = name,
            type = type.dbValue,
            sortOrder = sortOrder.toLong(),
            isArchived = if (isArchived) 1L else 0L,
            updatedAt = updatedAt,
            accountId = accountId,
        )
    }

    override suspend fun setArchived(accountId: String, archived: Boolean, updatedAt: Long) =
        withContext(dispatcher) {
            queries.setArchived(
                isArchived = if (archived) 1L else 0L,
                updatedAt = updatedAt,
                accountId = accountId,
            )
        }

    override suspend fun setSortOrder(accountId: String, sortOrder: Int, updatedAt: Long) =
        withContext(dispatcher) {
            queries.setSortOrder(
                sortOrder = sortOrder.toLong(),
                updatedAt = updatedAt,
                accountId = accountId,
            )
        }

    override suspend fun setCurrentBalance(accountId: String, balanceCents: Long, updatedAt: Long) =
        withContext(dispatcher) {
            queries.setCurrentBalance(
                currentBalance = balanceCents,
                updatedAt = updatedAt,
                accountId = accountId,
            )
        }

    override suspend fun nextSortOrder(): Int = withContext(dispatcher) {
        (queries.maxSortOrder().executeAsOne() + 1L).toInt()
    }

    override suspend fun count(): Long = withContext(dispatcher) {
        queries.countAll().executeAsOne()
    }
}
