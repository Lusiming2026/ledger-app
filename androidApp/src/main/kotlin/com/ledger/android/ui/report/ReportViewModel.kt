package com.ledger.android.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.domain.model.ReportData
import com.ledger.domain.model.ReportGranularity
import com.ledger.domain.model.ReportPeriod
import com.ledger.domain.port.ClockPort
import com.ledger.domain.usecase.GetReportUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReportUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val granularity: ReportGranularity = ReportGranularity.MONTH,
    val period: ReportPeriod? = null,
    val data: ReportData? = null,
    /** 是否已回到「当前」周期，用于禁用「下一期」 */
    val atLatest: Boolean = true,
)

/**
 * P11 报表 ViewModel（PRD §7.2 / §11.5，T18）。
 *
 * 口径完全交给 `GetReportUseCase`（`transfer` 不参与收支、分类占比只看支出），
 * 这里只负责「周期切换」与「下钻入口」两件事。
 */
class ReportViewModel(
    private val getReport: GetReportUseCase,
    private val clock: ClockPort,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    init {
        load(ReportPeriod.month(clock.currentYearMonth()), ReportGranularity.MONTH)
    }

    fun switchGranularity(granularity: ReportGranularity) {
        if (_state.value.granularity == granularity) return
        val period = when (granularity) {
            // 切到「周」时锚定到今天所在的自然周（PRD §7.2：周一至周日）
            ReportGranularity.WEEK -> ReportPeriod.week(clock.nowUtcMillis())
            ReportGranularity.MONTH -> ReportPeriod.month(clock.currentYearMonth())
        }
        load(period, granularity)
    }

    /** [delta] = ±1，按当前粒度前后翻一期。 */
    fun shift(delta: Int) {
        val current = _state.value.period ?: return
        load(current.shift(delta), _state.value.granularity)
    }

    fun refresh() {
        _state.value.period?.let { load(it, _state.value.granularity) }
    }

    private fun load(period: ReportPeriod, granularity: ReportGranularity) {
        _state.update { it.copy(loading = true, granularity = granularity) }
        viewModelScope.launch {
            try {
                val data = getReport.report(period)
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        period = period,
                        data = data,
                        atLatest = isAtLatest(period, granularity),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(loading = false, error = t.message ?: "报表加载失败", period = period)
                }
            }
        }
    }

    private fun isAtLatest(period: ReportPeriod, granularity: ReportGranularity): Boolean {
        val now = clock.nowUtcMillis()
        return when (granularity) {
            ReportGranularity.MONTH -> {
                val current = ReportPeriod.month(clock.currentYearMonth())
                period.startMillis == current.startMillis
            }

            ReportGranularity.WEEK -> {
                val current = ReportPeriod.week(now)
                period.startMillis == current.startMillis
            }
        }
    }

    companion object {
        fun factory() = KoinViewModelFactory {
            ReportViewModel(getReport = inject(), clock = inject())
        }
    }
}
