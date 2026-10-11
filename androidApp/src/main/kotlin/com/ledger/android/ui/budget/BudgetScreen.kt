package com.ledger.android.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.android.ui.components.ConfirmDialog
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.LoadingState
import com.ledger.android.ui.components.SectionCard
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.android.util.Ids
import com.ledger.domain.error.DomainError
import com.ledger.domain.model.BudgetStatus
import com.ledger.domain.model.Money
import com.ledger.domain.model.YearMonth
import com.ledger.domain.port.ClockPort
import com.ledger.domain.port.NotificationSchedulerPort
import com.ledger.domain.service.BudgetService
import com.ledger.domain.usecase.ManageBudgetUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BudgetUiState(
    val loading: Boolean = true,
    val yearMonth: YearMonth? = null,
    val status: BudgetStatus? = null,
    val warningLevel: Int = 0,
    val amountText: String = "",
    val notificationsAllowed: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
) {
    val amountCents: Long? get() = Money.parseYuan(amountText)?.cents

    val canSave: Boolean
        get() = (amountCents ?: 0L) > 0L && (amountCents ?: 0L) <= Money.MAX_AMOUNT_CENTS && !saving
}

/**
 * P10 预算页（PRD §7.3 / §11.4）。
 *
 * MVP 只有「月度总预算」一个口径，`UNIQUE(year, month)` 保证每月一条。
 * 编辑金额**不重置**已产生的通知记录（由 `BudgetRepository.upsert` 复用同月 budget_id 保证）。
 */
class BudgetViewModel(
    private val manageBudget: ManageBudgetUseCase,
    private val budgetService: BudgetService,
    private val notificationScheduler: NotificationSchedulerPort,
    private val clock: ClockPort,
) : ViewModel() {

    private val _state = MutableStateFlow(BudgetUiState())
    val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val yearMonth = clock.currentYearMonth()
            val status = manageBudget.status(yearMonth)
            _state.update {
                it.copy(
                    loading = false,
                    yearMonth = yearMonth,
                    status = status,
                    warningLevel = budgetService.inAppWarningLevel(status),
                    amountText = status.budget?.let { budget -> Fmt.plainYuan(budget.amount) }.orEmpty(),
                    notificationsAllowed = notificationScheduler.hasPermission(),
                )
            }
        }
    }

    fun setAmountText(value: String) {
        if (value.all { it.isDigit() || it == '.' }) {
            _state.update { it.copy(amountText = value) }
        }
    }

    fun save() {
        val current = _state.value
        val cents = current.amountCents ?: return
        val yearMonth = current.yearMonth ?: return
        if (!current.canSave) return

        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                manageBudget.setBudget(
                    // 复用同月既有的 budget_id，保住通知幂等链（PRD §7.3）
                    budgetId = current.status?.budget?.budgetId ?: Ids.budget(),
                    yearMonth = yearMonth,
                    amountCents = cents,
                )
                // 设完立刻检查一次：如果本月已经花超了，应当马上提醒
                manageBudget.checkAndNotify(yearMonth)
                _state.update { it.copy(saving = false, message = "已保存本月预算") }
                load()
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

    fun delete() {
        val budget = _state.value.status?.budget ?: return
        viewModelScope.launch {
            try {
                manageBudget.deleteBudget(budget.budgetId)
                _state.update { it.copy(amountText = "", message = "已删除本月预算") }
                load()
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "删除失败") }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory() = KoinViewModelFactory {
            BudgetViewModel(
                manageBudget = inject(),
                budgetService = inject(),
                notificationScheduler = inject(),
                clock = inject(),
            )
        }
    }
}

/** P10 预算页（PRD §4.2 / §11.4）。 */
@Composable
fun BudgetScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: BudgetViewModel = viewModel(factory = BudgetViewModel.factory())
    val state by vm.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LedgerScreenScaffold(
        modifier = modifier,
        title = "预算",
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@LedgerScreenScaffold
        }

        val status = state.status
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = state.yearMonth?.displayLabel ?: "本月") {
                if (status == null || !status.hasBudget) {
                    Text(
                        text = "本月还没有预算。设一个总额，花到 80% 和 100% 时会提醒你。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@SectionCard
                }

                val accent = LedgerSemanticColors.forWarningLevel(state.warningLevel)
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已支出 " + Fmt.money(status.spentCents),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "预算 " + Fmt.money(status.budgetCents),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = Fmt.percent(status.usageRatio),
                        style = MaterialTheme.typography.headlineSmall,
                        color = accent,
                    )
                }

                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { status.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (status.remainingCents >= 0) {
                        "剩余 " + Fmt.money(status.remainingCents)
                    } else {
                        "已超支 " + Fmt.money(-status.remainingCents)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.remainingCents >= 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        LedgerSemanticColors.Expense
                    },
                )
            }

            OutlinedTextField(
                value = state.amountText,
                onValueChange = vm::setAmountText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("月度总预算（元）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("只有一个月度总额，不分分类（MVP 口径）") },
            )

            Button(
                onClick = vm::save,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (state.saving) "保存中…" else "保存预算")
            }

            if (status?.hasBudget == true) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("删除本月预算", color = MaterialTheme.colorScheme.error)
                }
            }

            if (!state.notificationsAllowed) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = LedgerIcons.Warning,
                            contentDescription = null,
                            tint = LedgerSemanticColors.BudgetWarning,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "系统通知权限未开启：达到 80% / 100% 时不会推送通知，" +
                                "但上方进度条仍会变色预警（PRD §11.4）。" +
                                "可到「系统设置 → 通知」里为本应用开启。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = "预算按自然月生效，每月一条记录；切换月份后新周期自动生效，历史预算保留。" +
                    "编辑金额不会重置已经发过的提醒，删除预算会一并删除它的提醒记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除本月预算？",
            text = "删除后本月不再有预算提醒，且该预算的通知记录会一并删除。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
