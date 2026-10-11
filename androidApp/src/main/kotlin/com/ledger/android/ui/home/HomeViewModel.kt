package com.ledger.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.domain.model.Account
import com.ledger.domain.model.BudgetStatus
import com.ledger.domain.model.ReportSummary
import com.ledger.domain.model.Transaction
import com.ledger.domain.model.YearMonth
import com.ledger.domain.port.ClockPort
import com.ledger.domain.service.BudgetService
import com.ledger.domain.usecase.AddTransactionUseCase
import com.ledger.domain.usecase.DeleteTransactionInput
import com.ledger.domain.usecase.GetHomeOverviewUseCase
import com.ledger.domain.usecase.ManageBudgetUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** P02 首页状态。 */
data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val yearMonth: YearMonth? = null,
    val summary: ReportSummary = ReportSummary.EMPTY,
    val budgetStatus: BudgetStatus? = null,
    /** 0 正常 / 1 达 80% / 2 达 100%（通知被拒时的 App 内兜底，PRD §11.4） */
    val budgetWarningLevel: Int = 0,
    val accounts: List<Account> = emptyList(),
    val totalBalanceCents: Long = 0L,
    val recent: List<Transaction> = emptyList(),
    /** 最近流水只带了 `from_account_id`，展示账户名需要这张表（避免每条再查库） */
    val accountNames: Map<String, String> = emptyMap(),
    val message: String? = null,
)

/**
 * 首页 ViewModel（T16，PRD §4.2 P02）。
 *
 * 两条数据通道刻意分开：
 *  - 账户与最近流水走 **Flow 订阅**（`observeCards`）——保存一笔后立刻刷新（PRD §11.1 < 200ms）；
 *  - 本月汇总与预算状态走 **一次性查询**（聚合 SQL），在进入首页时刷新。
 *
 * 不把汇总也做成 Flow：SUM 查询没有 SQLDelight 的 `asFlow()`（那需要 `.sq` 里写
 * `selectSum: ... ;` 并配 `Query` 通知），订阅一个「每次都要重算全月」的流
 * 反而比「进页面算一次」更慢；而用户感知的实时性主要由账户余额与流水列表承担。
 */
class HomeViewModel(
    private val getHome: GetHomeOverviewUseCase,
    private val manageBudget: ManageBudgetUseCase,
    private val addTransaction: AddTransactionUseCase,
    private val budgetService: BudgetService,
    private val clock: ClockPort,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        observeLive()
        refresh()
    }

    /** 账户 + 最近流水的订阅（保存后即时刷新）。 */
    private fun observeLive() {
        viewModelScope.launch {
            getHome.observeCards().collectLatest { (accounts, recent) ->
                _state.update {
                    it.copy(
                        accounts = accounts,
                        // 总余额含已归档账户（PRD：归档不改历史，余额仍是用户的钱）
                        totalBalanceCents = accounts.sumOf { account -> account.currentBalance },
                        recent = recent,
                        accountNames = accounts.associate { account -> account.accountId to account.name },
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                val overview = getHome.load()
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        yearMonth = overview.yearMonth,
                        summary = overview.summary,
                        budgetStatus = overview.budgetStatus,
                        budgetWarningLevel = overview.budgetStatus
                            ?.let { status -> budgetService.inAppWarningLevel(status) } ?: 0,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, error = t.message ?: "首页数据加载失败")
                }
            }
        }
    }

    /**
     * 记账完成后由 [com.ledger.android.ui.record.RecordViewModel] 的调用方触发，
     * 或者由首页自己在返回时触发——统一在这里做「预算阈值检查 + 通知」，
     * 保证「月内总支出刚刚跨过 80% / 100%」时立刻就发（PRD §7.3）。
     */
    fun checkBudgetNotification() {
        val yearMonth = _state.value.yearMonth ?: clock.currentYearMonth()
        viewModelScope.launch {
            runCatching { manageBudget.checkAndNotify(yearMonth) }
            refresh()
        }
    }

    /**
     * P02 最近流水「左滑删除」（PRD §4.2）。
     *
     * 走标准软删除用例：余额回滚 + 乐观锁校验在同一事务内完成。
     * 删除只会**降低**当月总支出，不会产生新的阈值通知，因此不调 `checkAndNotify`。
     */
    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch {
            try {
                val result = addTransaction.delete(
                    DeleteTransactionInput(
                        transactionId = transaction.transactionId,
                        expectedUpdatedAtMillis = transaction.updatedAt,
                    ),
                )
                _state.update {
                    it.copy(message = if (result.deleted) "已删除，余额已回滚" else "该流水已被删除")
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "删除失败") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        fun factory() = KoinViewModelFactory {
            HomeViewModel(
                getHome = inject(),
                manageBudget = inject(),
                addTransaction = inject(),
                budgetService = inject(),
                clock = inject(),
            )
        }
    }
}
