package com.ledger.android.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.android.ui.format.Fmt
import com.ledger.android.util.Ids
import com.ledger.domain.error.DomainError
import com.ledger.domain.error.TransactionError
import com.ledger.domain.model.Account
import com.ledger.domain.model.Category
import com.ledger.domain.model.CategoryKind
import com.ledger.domain.model.Money
import com.ledger.domain.model.Transaction
import com.ledger.domain.model.TransactionType
import com.ledger.domain.port.ClockPort
import com.ledger.domain.port.PreferenceKeys
import com.ledger.domain.port.PreferencePort
import com.ledger.domain.repository.TransactionRepository
import com.ledger.domain.usecase.AddTransactionInput
import com.ledger.domain.usecase.AddTransactionUseCase
import com.ledger.domain.usecase.DeleteTransactionInput
import com.ledger.domain.usecase.ManageAccountUseCase
import com.ledger.domain.usecase.ManageBudgetUseCase
import com.ledger.domain.usecase.ManageCategoryUseCase
import com.ledger.domain.usecase.TransferInput
import com.ledger.domain.usecase.TransferUseCase
import com.ledger.domain.usecase.UpdateTransactionInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** P03/P04 记账页状态。 */
data class RecordUiState(
    val loading: Boolean = true,
    /** true = 编辑既有流水（P04），此时 `type` 不可改 */
    val isEditMode: Boolean = false,
    val type: TransactionType = TransactionType.EXPENSE,
    val amountText: String = "",
    val categoryId: String? = null,
    val fromAccountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
    val occurredAtMillis: Long = 0L,
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
    /** 保存成功 → UI 关闭本页 */
    val finished: Boolean = false,
    val deleted: Boolean = false,
) {
    /** 当前类型下要展示的分类（transfer 无分类）。 */
    val visibleCategories: List<Category>
        get() = when (type) {
            TransactionType.EXPENSE -> expenseCategories
            TransactionType.INCOME -> incomeCategories
            TransactionType.TRANSFER -> emptyList()
        }

    val amountCents: Long? get() = Money.parseYuan(amountText)?.cents

    /**
     * 是否可保存。只做「够不够」的判断，具体规则（上限 / 日期范围 / 备注长度 /
     * 分类账户状态）全部交给领域层，避免 UI 与领域各维护一份校验。
     */
    val canSave: Boolean
        get() {
            val cents = amountCents ?: return false
            if (cents <= 0L || cents > Money.MAX_AMOUNT_CENTS) return false
            if (saving) return false
            return when (type) {
                TransactionType.EXPENSE -> fromAccountId != null && categoryId != null
                TransactionType.INCOME -> toAccountId != null && categoryId != null
                TransactionType.TRANSFER ->
                    fromAccountId != null && toAccountId != null && fromAccountId != toAccountId
            }
        }
}

/**
 * P03 快速记账 / P04 编辑（PRD §5.1 / §11.1，"核心闭环 ≤ 3 步"）。
 *
 * 关键设计：
 *  - **类型切换 100% 发生在 select 之前**：切换 type 时按新类型回填「上次用过的分类」，
 *    并清掉不适用的分类（支出分类不能挂到收入流水上，R-CAT-04）；
 *  - **默认值来自 [PreferencePort]**：类型、分类（按 kind 分别记忆）、账户、日期。
 *    这正是 PRD §11.1「仅输入金额即可成单」的实现方式；
 *  - **不自己算余额**：一律走 `AddTransactionUseCase` / `TransferUseCase`，
 *    余额联动与乐观锁都在领域层同一事务里完成。
 */
class RecordViewModel(
    private val addTransaction: AddTransactionUseCase,
    private val transfer: TransferUseCase,
    private val manageCategory: ManageCategoryUseCase,
    private val manageAccount: ManageAccountUseCase,
    private val manageBudget: ManageBudgetUseCase,
    private val transactionRepository: TransactionRepository,
    private val preference: PreferencePort,
    private val clock: ClockPort,
    /** 非空 = 编辑模式（P04） */
    private val editingTransactionId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(RecordUiState())
    val state: StateFlow<RecordUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val expense = manageCategory.getActive(CategoryKind.EXPENSE)
        val income = manageCategory.getActive(CategoryKind.INCOME)
        val accounts = manageAccount.getActive()
        val now = clock.nowUtcMillis()

        val editing = editingTransactionId?.let { transactionRepository.getById(it) }

        if (editing != null) {
            _state.update {
                it.copy(
                    loading = false,
                    isEditMode = true,
                    type = editing.type,
                    amountText = Fmt.plainYuan(editing.amount),
                    categoryId = editing.categoryId,
                    fromAccountId = editing.fromAccountId,
                    toAccountId = editing.toAccountId,
                    note = editing.note.orEmpty(),
                    occurredAtMillis = editing.occurredAt,
                    expenseCategories = expense,
                    incomeCategories = income,
                    accounts = accounts,
                )
            }
            return
        }

        // 新增：按 PRD §11.1 用「上次值 / 今天」预填
        val lastType = TransactionType.fromDbOrNull(
            preference.getString(PreferenceKeys.LAST_TRANSACTION_TYPE),
        ) ?: TransactionType.EXPENSE

        _state.update {
            it.copy(
                loading = false,
                isEditMode = false,
                type = lastType,
                amountText = "",
                categoryId = lastCategoryFor(lastType, expense, income),
                fromAccountId = lastAccountId(PreferenceKeys.LAST_FROM_ACCOUNT_ID, accounts),
                toAccountId = lastAccountId(PreferenceKeys.LAST_TO_ACCOUNT_ID, accounts, skipFirst = true),
                note = "",
                occurredAtMillis = now,
                expenseCategories = expense,
                incomeCategories = income,
                accounts = accounts,
            )
        }
    }

    // ------------------------------------------------------------- 输入

    fun setType(type: TransactionType) {
        val current = _state.value
        if (current.isEditMode || current.type == type) return
        _state.update {
            it.copy(
                type = type,
                // 切类型时分类必须跟着变，否则会触发 R-CAT-04
                categoryId = lastCategoryFor(type, it.expenseCategories, it.incomeCategories),
            )
        }
    }

    fun selectCategory(categoryId: String) {
        _state.update { it.copy(categoryId = categoryId) }
    }

    fun selectFromAccount(accountId: String) {
        _state.update { it.copy(fromAccountId = accountId) }
    }

    fun selectToAccount(accountId: String) {
        _state.update { it.copy(toAccountId = accountId) }
    }

    /**
     * 转账时点同一个账户 = 想把它设成「转出」。
     * 直接无视这一次点击并给出提示，比存下一个非法状态更好（R-TRF-01）。
     */
    fun swapAccounts() {
        _state.update {
            it.copy(fromAccountId = it.toAccountId, toAccountId = it.fromAccountId)
        }
    }

    fun setNote(note: String) {
        // 领域层会做 50 字校验；这里只挡住溢出输入，避免用户敲了半天才被拒
        if (note.length <= 50) _state.update { it.copy(note = note) }
    }

    fun setOccurredAt(millis: Long) {
        _state.update { it.copy(occurredAtMillis = millis) }
    }

    // ------------------------------------------------------------- 金额键盘

    fun pressDigit(digit: Char) {
        _state.update { current ->
            val text = current.amountText
            val dot = text.indexOf('.')
            if (dot >= 0 && text.length - dot > 2) return@update current   // 已到「分」精度
            if (dot < 0 && text.replace(".", "").length >= 7) return@update current // 上限 9,999,999
            val next = if (text == "0") digit.toString() else text + digit
            current.copy(amountText = next)
        }
    }

    fun pressDot() {
        _state.update { current ->
            when {
                current.amountText.contains('.') -> current
                current.amountText.isEmpty() -> current.copy(amountText = "0.")
                else -> current.copy(amountText = current.amountText + ".")
            }
        }
    }

    fun pressDoubleZero() {
        pressDigit('0')
        pressDigit('0')
    }

    fun backspace() {
        _state.update { current ->
            if (current.amountText.isEmpty()) current
            else current.copy(amountText = current.amountText.dropLast(1))
        }
    }

    fun clearAmount() {
        _state.update { it.copy(amountText = "") }
    }

    // ------------------------------------------------------------- 保存 / 删除

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            try {
                if (current.isEditMode) {
                    updateExisting(current)
                } else {
                    createNew(current)
                }
                rememberLastChoices(current)
                // 记账是当月总支出唯一的变化来源：保存后立刻做阈值判定（PRD §7.3）
                runCatching { manageBudget.checkAndNotify(clock.currentYearMonth()) }
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

    private suspend fun createNew(current: RecordUiState) {
        val amount = current.amountCents ?: throw TransactionError.InvalidAmount

        if (current.type == TransactionType.TRANSFER) {
            transfer.execute(
                TransferInput(
                    transactionId = Ids.transaction(),
                    amountCents = amount,
                    fromAccountId = requireNotNull(current.fromAccountId),
                    toAccountId = requireNotNull(current.toAccountId),
                    note = current.note,
                    occurredAtMillis = current.occurredAtMillis,
                ),
            )
        } else {
            addTransaction.execute(
                AddTransactionInput(
                    transactionId = Ids.transaction(),
                    type = current.type,
                    amountCents = amount,
                    categoryId = current.categoryId,
                    fromAccountId = current.fromAccountId,
                    toAccountId = current.toAccountId,
                    note = current.note,
                    occurredAtMillis = current.occurredAtMillis,
                ),
            )
        }
    }

    private suspend fun updateExisting(current: RecordUiState) {
        val id = editingTransactionId ?: return
        val existing: Transaction = transactionRepository.getById(id)
            ?: throw TransactionError.TransactionNotFound
        val amount = current.amountCents ?: throw TransactionError.InvalidAmount

        addTransaction.update(
            UpdateTransactionInput(
                transactionId = id,
                // 乐观锁版本：读时值必须与库中一致，否则领域层抛 ConcurrentModification
                expectedUpdatedAtMillis = existing.updatedAt,
                amountCents = amount,
                categoryId = current.categoryId,
                fromAccountId = current.fromAccountId,
                toAccountId = current.toAccountId,
                note = current.note,
                occurredAtMillis = current.occurredAtMillis,
            ),
        )
    }

    fun deleteExisting() {
        val id = editingTransactionId ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val existing = transactionRepository.getById(id)
                    ?: throw TransactionError.TransactionNotFound
                addTransaction.delete(DeleteTransactionInput(id, existing.updatedAt))
                _state.update { it.copy(saving = false, finished = true, deleted = true) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(saving = false, message = t.message ?: "删除失败")
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------- 内部

    /** 上次用过的分类；该分类可能已被删除/归档，因此每次都做一次存在性校验。 */
    private fun lastCategoryFor(
        type: TransactionType,
        expense: List<Category>,
        income: List<Category>,
    ): String? {
        val kind: CategoryKind = CategoryKind.of(type) ?: return null
        val remembered = preference.getString(PreferenceKeys.lastCategoryId(kind.dbValue))
        val pool = if (kind == CategoryKind.EXPENSE) expense else income
        return remembered?.takeIf { id -> pool.any { it.categoryId == id } }
            ?: pool.firstOrNull()?.categoryId
    }

    private fun lastAccountId(
        key: String,
        accounts: List<Account>,
        skipFirst: Boolean = false,
    ): String? {
        val remembered = preference.getString(key)
        remembered?.takeIf { id -> accounts.any { it.accountId == id } }?.let { return it }
        // 转账的「转入账户」给一个不同的默认值，避免用户一进来就撞上 R-TRF-01
        return if (skipFirst) accounts.getOrNull(1)?.accountId ?: accounts.firstOrNull()?.accountId
        else accounts.firstOrNull()?.accountId
    }

    /**
     * 记下本次选择，供下次记账预填（PRD §11.1「记忆上次值」）。
     *
     * 「上次账户」按角色分别记忆：支出记住转出账户、收入记住转入账户，
     * 转账则两个都记。这样「天天用微信付饭钱」的用户第二天打开就已经选好了。
     */
    private fun rememberLastChoices(current: RecordUiState) {
        preference.putString(PreferenceKeys.LAST_TRANSACTION_TYPE, current.type.dbValue)

        CategoryKind.of(current.type)?.let { kind ->
            preference.putString(PreferenceKeys.lastCategoryId(kind.dbValue), current.categoryId)
        }

        when (current.type) {
            TransactionType.EXPENSE -> {
                preference.putString(PreferenceKeys.LAST_FROM_ACCOUNT_ID, current.fromAccountId)
                preference.putString(PreferenceKeys.LAST_ACCOUNT_ID, current.fromAccountId)
            }

            TransactionType.INCOME -> {
                preference.putString(PreferenceKeys.LAST_TO_ACCOUNT_ID, current.toAccountId)
                preference.putString(PreferenceKeys.LAST_ACCOUNT_ID, current.toAccountId)
            }

            TransactionType.TRANSFER -> {
                preference.putString(PreferenceKeys.LAST_FROM_ACCOUNT_ID, current.fromAccountId)
                preference.putString(PreferenceKeys.LAST_TO_ACCOUNT_ID, current.toAccountId)
            }
        }
    }

    companion object {
        fun factory(transactionId: String?) = KoinViewModelFactory {
            RecordViewModel(
                addTransaction = inject(),
                transfer = inject(),
                manageCategory = inject(),
                manageAccount = inject(),
                manageBudget = inject(),
                transactionRepository = inject(),
                preference = inject(),
                clock = inject(),
                editingTransactionId = transactionId,
            )
        }
    }
}
