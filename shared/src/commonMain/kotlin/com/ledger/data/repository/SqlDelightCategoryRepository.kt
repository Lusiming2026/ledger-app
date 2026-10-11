package com.ledger.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.ledger.data.db.LedgerDatabase
import com.ledger.data.mapper.toDomain
import com.ledger.domain.model.Category
import com.ledger.domain.model.CategoryKind
import com.ledger.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [CategoryRepository] 的 SQLDelight 实现（T08）。
 *
 * `UNIQUE(kind, name)` 由索引 `uq_category_name` 兜底；重名写入会抛底层 SQLite 约束异常，
 * 由用例捕获并转成领域错误（`CatalogError.DuplicateName`）。
 */
class SqlDelightCategoryRepository(
    private val database: LedgerDatabase,
    private val dispatcher: CoroutineDispatcher,
) : CategoryRepository {

    private val queries = database.categoryQueries

    override fun observeAll(): Flow<List<Category>> =
        queries.selectAll()
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeActive(kind: CategoryKind): Flow<List<Category>> =
        queries.selectActiveByKind(kind = kind.dbValue)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<Category> = withContext(dispatcher) {
        queries.selectAll().executeAsList().map { it.toDomain() }
    }

    override suspend fun getActive(kind: CategoryKind): List<Category> = withContext(dispatcher) {
        queries.selectActiveByKind(kind = kind.dbValue).executeAsList().map { it.toDomain() }
    }

    override suspend fun getByKind(kind: CategoryKind): List<Category> = withContext(dispatcher) {
        queries.selectByKind(kind = kind.dbValue).executeAsList().map { it.toDomain() }
    }

    override suspend fun getById(categoryId: String): Category? = withContext(dispatcher) {
        queries.selectById(categoryId = categoryId).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun findByKindAndName(kind: CategoryKind, name: String): Category? =
        withContext(dispatcher) {
            queries.selectByKindAndName(kind = kind.dbValue, name = name)
                .executeAsOneOrNull()
                ?.toDomain()
        }

    override suspend fun insert(category: Category) = withContext(dispatcher) {
        queries.insertCategory(
            categoryId = category.categoryId,
            name = category.name,
            kind = category.kind.dbValue,
            parentId = category.parentId,
            icon = category.icon,
            color = category.color,
            isSystem = if (category.isSystem) 1L else 0L,
            sortOrder = category.sortOrder.toLong(),
            isArchived = if (category.isArchived) 1L else 0L,
        )
    }

    override suspend fun updateProfile(
        categoryId: String,
        name: String,
        icon: String,
        color: String,
    ) = withContext(dispatcher) {
        queries.updateCategoryProfile(
            name = name,
            icon = icon,
            color = color,
            categoryId = categoryId,
        )
    }

    override suspend fun setArchived(categoryId: String, archived: Boolean) =
        withContext(dispatcher) {
            queries.setCategoryArchived(
                isArchived = if (archived) 1L else 0L,
                categoryId = categoryId,
            )
        }

    override suspend fun setSortOrder(categoryId: String, sortOrder: Int) = withContext(dispatcher) {
        queries.setCategorySortOrder(
            sortOrder = sortOrder.toLong(),
            categoryId = categoryId,
        )
    }

    override suspend fun deleteHard(categoryId: String) = withContext(dispatcher) {
        queries.deleteCategory(categoryId = categoryId)
    }

    override suspend fun nextSortOrder(kind: CategoryKind): Int = withContext(dispatcher) {
        (queries.maxSortOrderByKind(kind = kind.dbValue).executeAsOne() + 1L).toInt()
    }

    override suspend fun count(): Long = withContext(dispatcher) {
        queries.countAll().executeAsOne()
    }

    override suspend fun seedIfEmpty(seed: List<Category>) = withContext(dispatcher) {
        // 幂等：逐条按 (kind, name) 判重，已存在则跳过。
        // 不整体清空重写——用户可能已经改过预置分类的图标/颜色/排序。
        database.transaction {
            seed.forEach { category ->
                val exists = queries
                    .selectByKindAndName(kind = category.kind.dbValue, name = category.name)
                    .executeAsOneOrNull() != null
                if (!exists) {
                    queries.insertCategory(
                        categoryId = category.categoryId,
                        name = category.name,
                        kind = category.kind.dbValue,
                        parentId = category.parentId,
                        icon = category.icon,
                        color = category.color,
                        isSystem = if (category.isSystem) 1L else 0L,
                        sortOrder = category.sortOrder.toLong(),
                        isArchived = if (category.isArchived) 1L else 0L,
                    )
                }
            }
        }
    }
}
