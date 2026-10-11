package com.ledger.android.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.android.util.Ids
import com.ledger.domain.error.DomainError
import com.ledger.domain.model.Account
import com.ledger.domain.model.AccountType
import com.ledger.domain.model.Money
import com.ledger.domain.model.Reconciliation
import com.ledger.domain.usecase.ManageAccountUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============================================================ P07 列表

data class AccountListUiState(
    val accounts: List<Account> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
)

/** P07 账户管理（PRD §11.3）。账户**只能归档、不能删除**。 */
class AccountListViewModel(
    private val manageAccount: ManageAccountUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountListUiState())
    val state: StateFlow<AccountListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            manageAccount.observeAll().collect { accounts ->
                _state.update { it.copy(accounts = accounts, loading = false) }
            }
        }
    }

    fun setArchived(accountId: String, archived: Boolean) {
        viewModelScope.launch {
            try {
                manageAccount.setArchived(accountId, archived)
                _state.update {
                    it.copy(message = if (archived) "已归档，记账时不再出现" else "已取消归档")
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "操作失败") }
            }
        }
    }

    /** 拖拽排序落地：整表按新顺序重写 `sort_order`。 */
    fun reorder(ordered: List<Account>) {
        viewModelScope.launch {
            try {
                manageAccount.reorderAccount(ordered.map { it.accountId })
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "排序失败") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory() = KoinViewModelFactory { AccountListViewModel(manageAccount = inject()) }
    }
}

// ============================================================ P08 新增 / 编辑

data class AccountEditUiState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val name: String = "",
    val type: AccountType = AccountType.CASH,
    /** 元（字符串），仅在新增时可编辑 */
    val initialBalanceText: String = "",
    val currentBalanceCents: Long = 0L,
    val existing: Account? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val finished: Boolean = false,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && !saving &&
            (isEdit || Money.parseYuan(initialBalanceText) != null)
}

/** P08 账户编辑（PRD §11.3：初始余额创建后不可修改）。 */
class AccountEditViewModel(
    private val manageAccount: ManageAccountUseCase,
    private val accountId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountEditUiState())
    val state: StateFlow<AccountEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = accountId?.let { manageAccount.getById(it) }
            _state.update {
                if (existing != null) {
                    it.copy(
                        loading = false,
                        isEdit = true,
                        name = existing.name,
                        type = existing.type,
                        initialBalanceText = com.ledger.android.ui.format.Fmt.plainYuan(existing.initialBalance),
                        currentBalanceCents = existing.currentBalance,
                        existing = existing,
                    )
                } else {
                    // 新账户默认初始余额 0 元；空字符串会被 Money.parseYuan 判为非数字而挡住保存
                    it.copy(loading = false, isEdit = false, initialBalanceText = "0")
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setType(value: AccountType) = _state.update { it.copy(type = value) }

    fun setInitialBalance(value: String) {
        // 只允许数字、小数点和负号（初始余额可为负，例如信用卡欠款）
        if (value.all { it.isDigit() || it == '.' || it == '-' }) {
            _state.update { it.copy(initialBalanceText = value) }
        }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            try {
                if (current.isEdit) {
                    manageAccount.update(
                        accountId = requireNotNull(accountId),
                        name = current.name,
                        type = current.type,
                    )
                } else {
                    manageAccount.create(
                        accountId = Ids.account(),
                        name = current.name,
                        type = current.type,
                        initialBalanceCents = Money.parseYuan(current.initialBalanceText)?.cents ?: 0L,
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

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(accountId: String?) = KoinViewModelFactory {
            AccountEditViewModel(manageAccount = inject(), accountId = accountId)
        }
    }
}

// ============================================================ P09 对账

data class ReconcileUiState(
    val loading: Boolean = true,
    val account: Account? = null,
    /** 输入的「实际余额」，单位元 */
    val actualBalanceText: String = "",
    val note: String = "",
    val history: List<Reconciliation> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
    val finished: Boolean = false,
) {
    val actualBalanceCents: Long? get() = Money.parseYuan(actualBalanceText)?.cents

    /** 与账面余额的差额（分）；正数表示账面少了钱，负数表示账面多了钱。 */
    val differenceCents: Long?
        get() {
            val actual = actualBalanceCents ?: return null
            val book = account?.currentBalance ?: return null
            return actual - book
        }

    val canSave: Boolean get() = actualBalanceCents != null && !saving
}

/**
 * P09 对账（PRD §6.2.6 / §11.3）。
 *
 * 语义：输入实际余额 → **直接覆盖账面余额** + 写一条 `reconciliation` 留痕，
 * **不生成差额调整流水**（这是 PRD 明确的选择：差额往往来自漏记/多记，
 * 自动造一笔「调整」流水会污染报表口径）。
 */
class ReconcileViewModel(
    private val manageAccount: ManageAccountUseCase,
    private val accountId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ReconcileUiState())
    val state: StateFlow<ReconcileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val account = manageAccount.getById(accountId)
            _state.update {
                it.copy(
                    loading = false,
                    account = account,
                    actualBalanceText = account?.let { a ->
                        com.ledger.android.ui.format.Fmt.plainYuan(a.currentBalance)
                    }.orEmpty(),
                )
            }
        }
        viewModelScope.launch {
            manageAccount.observeReconciliations(accountId).collect { records ->
                _state.update { it.copy(history = records) }
            }
        }
    }

    fun setActualBalance(value: String) {
        if (value.all { it.isDigit() || it == '.' || it == '-' }) {
            _state.update { it.copy(actualBalanceText = value) }
        }
    }

    fun setNote(value: String) {
        _state.update { it.copy(note = value) }
    }

    fun save() {
        val current = _state.value
        val cents = current.actualBalanceCents ?: return
        if (!current.canSave) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            try {
                manageAccount.reconcile(
                    // reconciliationId 由调用方生成（与 transactionId 同一策略）
                    reconciliationId = Ids.reconciliation(),
                    accountId = accountId,
                    actualBalanceCents = cents,
                    note = current.note,
                )
                _state.update { it.copy(saving = false, finished = true, message = "已按实际余额校准") }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        message = (t as? DomainError)?.message ?: t.message ?: "对账失败",
                    )
                }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory(accountId: String) = KoinViewModelFactory {
            ReconcileViewModel(manageAccount = inject(), accountId = accountId)
        }
    }
}
