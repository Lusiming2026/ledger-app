package com.ledger.android.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.domain.model.TransactionDetail
import com.ledger.domain.model.TransactionType
import com.ledger.domain.usecase.AddTransactionUseCase
import com.ledger.domain.usecase.DeleteTransactionInput
import com.ledger.domain.usecase.GetReportUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 按「日」分组的展示单元（明细页的日期分组头 + 当日小计）。 */
data class DayGroup(
    val dayStartMillis: Long,
    val label: String,
    val expenseCents: Long,
    val incomeCents: Long,
    val items: List<TransactionDetail>,
)

data class TransactionListUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val groups: List<DayGroup> = emptyList(),
    val totalCount: Int = 0,
    val message: String? = null,
)

/**
 * P12 流水明细页（PRD §4.2 P12 / §11.5 图表下钻）。
 *
 * 同一个页面承担三种入口，靠参数区分：
 *  - 首页「查看全部」→ 全量；
 *  - 报表饼图下钻 → 指定 `categoryId`；
 *  - 报表趋势图下钻 → 指定一天的 `[from, to)`。
 *
 * 筛选放在内存里做（`allDetails` 一次取完区间数据），不额外加 SQL：
 * 区间本身已经被 SQL 缩到很小（一个月最多几百条），再加一条筛选语句只会多一处要维护的 SQL。
 * 若将来数据量真的上到十万级，`getActiveByCategoryBetween` 已经就绪，改一行即可。
 */
class TransactionListViewModel(
    private val getReport: GetReportUseCase,
    private val addTransaction: AddTransactionUseCase,
    private val fromMillis: Long,
    private val toMillis: Long,
    private val categoryId: String?,
    private val type: TransactionType?,
) : ViewModel() {

    private val _state = MutableStateFlow(TransactionListUiState())
    val state: StateFlow<TransactionListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                val all = getReport.allDetails(fromMillis, toMillis)
                val filtered = all.filter { detail ->
                    (categoryId == null || detail.transaction.categoryId == categoryId) &&
                        (type == null || detail.transaction.type == type)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        totalCount = filtered.size,
                        groups = groupByDay(filtered),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, error = t.message ?: "流水加载失败")
                }
            }
        }
    }

    fun delete(detail: TransactionDetail) {
        viewModelScope.launch {
            try {
                val transaction = detail.transaction
                val result = addTransaction.delete(
                    DeleteTransactionInput(transaction.transactionId, transaction.updatedAt),
                )
                _state.update {
                    it.copy(message = if (result.deleted) "已删除，余额已回滚" else "该流水已被删除")
                }
                load()
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "删除失败") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * 按「记账日期」所在的自然日分组，组内按发生时间倒序。
     * 用本地时区的日界（不是 UTC 日界），否则晚上 8 点后的流水会被分到第二天。
     */
    private fun groupByDay(details: List<TransactionDetail>): List<DayGroup> {
        if (details.isEmpty()) return emptyList()

        val byDay = details.groupBy { detail ->
            val millis = detail.transaction.occurredAt
            com.ledger.android.ui.format.Fmt.date(millis)
        }

        return byDay.entries
            .sortedByDescending { it.key }
            .map { (day, items) ->
                val sorted = items.sortedByDescending { it.transaction.occurredAt }
                DayGroup(
                    dayStartMillis = sorted.first().transaction.occurredAt,
                    label = day,
                    expenseCents = items
                        .filter { it.transaction.type == TransactionType.EXPENSE }
                        .sumOf { it.transaction.amount },
                    incomeCents = items
                        .filter { it.transaction.type == TransactionType.INCOME }
                        .sumOf { it.transaction.amount },
                    items = sorted,
                )
            }
    }

    companion object {
        fun factory(
            fromMillis: Long,
            toMillis: Long,
            categoryId: String?,
            type: TransactionType?,
        ) = KoinViewModelFactory {
            TransactionListViewModel(
                getReport = inject(),
                addTransaction = inject(),
                fromMillis = fromMillis,
                toMillis = toMillis,
                categoryId = categoryId,
                type = type,
            )
        }
    }
}
