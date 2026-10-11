package com.ledger.android.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.android.util.Ids
import com.ledger.domain.error.DomainError
import com.ledger.domain.model.Category
import com.ledger.domain.model.CategoryKind
import com.ledger.domain.repository.TransactionRepository
import com.ledger.domain.usecase.ManageCategoryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============================================================ P05 列表

data class CategoryListUiState(
    val loading: Boolean = true,
    val expense: List<Category> = emptyList(),
    val income: List<Category> = emptyList(),
    val message: String? = null,
)

/** P05 分类管理（PRD §11.2）。按支出/收入两组展示，支持长按拖拽排序与归档。 */
class CategoryListViewModel(
    private val manageCategory: ManageCategoryUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(CategoryListUiState())
    val state: StateFlow<CategoryListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            manageCategory.observeAll().collect { all ->
                _state.update {
                    it.copy(
                        loading = false,
                        expense = all.filter { c -> c.kind == CategoryKind.EXPENSE },
                        income = all.filter { c -> c.kind == CategoryKind.INCOME },
                    )
                }
            }
        }
    }

    fun setArchived(categoryId: String, archived: Boolean) {
        viewModelScope.launch {
            try {
                manageCategory.setArchived(categoryId, archived)
                _state.update {
                    it.copy(message = if (archived) "已归档，记账时不再出现" else "已取消归档")
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "操作失败") }
            }
        }
    }

    fun reorder(ordered: List<Category>) {
        viewModelScope.launch {
            try {
                manageCategory.reorder(ordered.map { it.categoryId })
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "排序失败") }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory() = KoinViewModelFactory { CategoryListViewModel(manageCategory = inject()) }
    }
}

// ============================================================ P06 新增 / 编辑

data class CategoryEditUiState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val name: String = "",
    val kind: CategoryKind = CategoryKind.EXPENSE,
    val icon: String = "more_horiz",
    val color: String = "#42A5F5",
    val isSystem: Boolean = false,
    val isArchived: Boolean = false,
    /** 删除该分类会影响的流水条数（>0 时必须选迁移目标） */
    val referencedCount: Int = 0,
    /** 迁移目标候选：同 kind 且未归档、且不是自己 */
    val migrationCandidates: List<Category> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
    val finished: Boolean = false,
) {
    val canSave: Boolean get() = name.isNotBlank() && !saving

    /** 预置分类不可删除（PRD §11.2）；有引用时还要选迁移目标。 */
    val canDelete: Boolean get() = isEdit && !isSystem
}

/** P06 分类编辑（PRD §4.2 / §11.2）。 */
class CategoryEditViewModel(
    private val manageCategory: ManageCategoryUseCase,
    private val transactionRepository: TransactionRepository,
    private val categoryId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(CategoryEditUiState())
    val state: StateFlow<CategoryEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val existing = categoryId?.let { id -> manageCategory.getAll().firstOrNull { it.categoryId == id } }
        if (existing == null) {
            _state.update { it.copy(loading = false, isEdit = false) }
            return
        }

        val refs = transactionRepository.countCategoryRefs(existing.categoryId)
        val candidates = manageCategory.getActive(existing.kind)
            .filter { it.categoryId != existing.categoryId }

        _state.update {
            it.copy(
                loading = false,
                isEdit = true,
                name = existing.name,
                kind = existing.kind,
                icon = existing.icon,
                color = existing.color,
                isSystem = existing.isSystem,
                isArchived = existing.isArchived,
                referencedCount = refs,
                migrationCandidates = candidates,
            )
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setIcon(value: String) = _state.update { it.copy(icon = value) }

    fun setColor(value: String) = _state.update { it.copy(color = value) }

    fun setKind(value: CategoryKind) {
        // 已存在的分类不允许改收支类型：历史流水已经按原 kind 挂靠，
        // 改 kind 会让「支出流水挂在收入分类上」直接违反 R-CAT-04。
        if (_state.value.isEdit) return
        _state.update { it.copy(kind = value) }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            try {
                if (current.isEdit) {
                    manageCategory.update(
                        categoryId = requireNotNull(categoryId),
                        name = current.name,
                        icon = current.icon,
                        color = current.color,
                    )
                } else {
                    manageCategory.create(
                        categoryId = Ids.category(),
                        name = current.name,
                        kind = current.kind,
                        icon = current.icon,
                        color = current.color,
                    )
                }
                _state.update { it.copy(saving = false, finished = true) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        message = (t as? DomainError)?.message ?: t.message ?: "保存失败",
                    )
                }
            }
        }
    }

    fun toggleArchived() {
        val current = _state.value
        if (!current.isEdit) return
        viewModelScope.launch {
            try {
                manageCategory.setArchived(requireNotNull(categoryId), !current.isArchived)
                _state.update { it.copy(isArchived = !current.isArchived, message = "已更新归档状态") }
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "操作失败") }
            }
        }
    }

    /**
     * 删除自定义分类。
     * @param migrateToCategoryId 有历史流水引用时必填（PRD §11.2 第 4 条）
     */
    fun delete(migrateToCategoryId: String?) {
        val current = _state.value
        if (!current.canDelete) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            try {
                val migrated = manageCategory.deleteWithMigration(
                    categoryId = requireNotNull(categoryId),
                    migrateToCategoryId = migrateToCategoryId,
                )
                _state.update {
                    it.copy(
                        saving = false,
                        finished = true,
                        message = "已删除，迁移了 $migrated 笔流水",
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        message = (t as? DomainError)?.message ?: t.message ?: "删除失败",
                    )
                }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(categoryId: String?) = KoinViewModelFactory {
            CategoryEditViewModel(
                manageCategory = inject(),
                transactionRepository = inject(),
                categoryId = categoryId,
            )
        }
    }
}
