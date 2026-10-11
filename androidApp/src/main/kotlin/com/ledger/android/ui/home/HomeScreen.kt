package com.ledger.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.ui.components.CircleIconBadge
import com.ledger.android.ui.components.ConfirmDialog
import com.ledger.android.ui.components.EmptyState
import com.ledger.android.ui.components.ErrorState
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.LoadingState
import com.ledger.android.ui.components.SectionCard
import com.ledger.android.ui.components.SimpleTransactionRow
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.model.Account
import com.ledger.domain.model.Transaction

/**
 * P02 首页总览（PRD §4.2 / §11.1）。
 *
 * 一屏看完四件事：本月收支 → 预算进度 → 账户余额 → 最近流水，
 * 右下角常驻「记一笔」，把「≤ 3 步记账」的第一步压到零成本。
 */
@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onOpenAllTransactions: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory())
    val state by vm.state.collectAsState()

    // 从记账页返回时立刻刷新汇总（账户/流水由订阅流自动更新，汇总需要重算）
    LaunchedEffect(Unit) {
        vm.refresh()
        vm.checkBudgetNotification()
    }

    var pendingDelete by remember { mutableStateOf<Transaction?>(null) }

    LedgerScreenScaffold(
        modifier = modifier,
        title = "记账",
        message = state.message,
        onMessageShown = vm::consumeMessage,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(LedgerIcons.Add, contentDescription = null) },
                text = { Text("记一笔") },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.padding(padding))
            state.error != null -> ErrorState(
                message = state.error!!,
                modifier = Modifier.padding(padding),
                onRetry = vm::refresh,
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item("overview") {
                    MonthOverviewCard(
                        monthLabel = state.yearMonth?.displayLabel ?: "本月",
                        incomeCents = state.summary.incomeCents,
                        expenseCents = state.summary.expenseCents,
                        balanceCents = state.summary.balanceCents,
                        onOpenReports = onOpenReports,
                    )
                }

                item("budget") {
                    BudgetProgressCard(
                        status = state.budgetStatus,
                        warningLevel = state.budgetWarningLevel,
                        onSetBudget = onOpenBudget,
                    )
                }

                item("accounts") {
                    AccountsCard(
                        accounts = state.accounts,
                        totalBalanceCents = state.totalBalanceCents,
                        onOpenAccounts = onOpenAccounts,
                    )
                }

                item("recent-title") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "最近流水",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenAllTransactions) { Text("查看全部") }
                    }
                }

                if (state.recent.isEmpty()) {
                    item("recent-empty") {
                        SectionCard {
                            EmptyState(
                                text = "还没有流水。点右下角「记一笔」，只输金额就能存下第一笔。",
                                actionLabel = "记一笔",
                                onAction = onRecord,
                            )
                        }
                    }
                } else {
                    items(state.recent, key = { it.transactionId }) { transaction ->
                        SwipeToDeleteRow(
                            transaction = transaction,
                            accountName = accountNameOf(transaction, state.accountNames),
                            onClick = { onOpenTransaction(transaction.transactionId) },
                            onRequestDelete = { pendingDelete = transaction },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除这笔流水？",
            text = "删除后余额会一并回滚。这是软删除，历史备份里不会包含它。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                vm.deleteTransaction(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 最近流水按类型取对应的账户名（支出看转出、收入看转入、转账优先显示转出）。 */
private fun accountNameOf(transaction: Transaction, names: Map<String, String>): String? =
    names[transaction.fromAccountId ?: transaction.toAccountId]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    transaction: Transaction,
    accountName: String?,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    // 返回 false = 卡片回弹，真正的删除交给二次确认弹窗（PRD §11.1「删除前有二次确认」）
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onRequestDelete()
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.shapes.medium,
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = LedgerIcons.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SimpleTransactionRow(
                transaction = transaction,
                accountName = accountName,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun MonthOverviewCard(
    monthLabel: String,
    incomeCents: Long,
    expenseCents: Long,
    balanceCents: Long,
    onOpenReports: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "$monthLabel 结余",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Fmt.signedMoney(balanceCents),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OverviewStat(
                    label = "收入",
                    value = Fmt.money(incomeCents),
                    color = LedgerSemanticColors.Income,
                    modifier = Modifier.weight(1f),
                )
                OverviewStat(
                    label = "支出",
                    value = Fmt.money(expenseCents),
                    color = LedgerSemanticColors.Expense,
                    modifier = Modifier.weight(1f),
                )
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(
                        text = "报表",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    TextButton(onClick = onOpenReports, contentPadding = PaddingValues(0.dp)) {
                        Text("看明细", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}

@Composable
private fun BudgetProgressCard(
    status: com.ledger.domain.model.BudgetStatus?,
    warningLevel: Int,
    onSetBudget: () -> Unit,
) {
    SectionCard(
        title = "本月预算",
        trailing = {
            TextButton(onClick = onSetBudget) {
                Text(if (status?.hasBudget == true) "调整" else "设置")
            }
        },
    ) {
        if (status == null || !status.hasBudget) {
            Text(
                text = "还没设预算。设一个月度总额，花费到 80% 和 100% 时会提醒你。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val accent = LedgerSemanticColors.forWarningLevel(warningLevel)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Fmt.money(status.spentCents) + " / " + Fmt.money(status.budgetCents),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (status.remainingCents >= 0) {
                        "剩余 " + Fmt.money(status.remainingCents)
                    } else {
                        "已超支 " + Fmt.money(-status.remainingCents)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.remainingCents >= 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        LedgerSemanticColors.Expense
                    },
                )
            }
            Text(
                text = Fmt.percent(status.usageRatio),
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { status.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        if (warningLevel > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = LedgerIcons.Warning,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    // 通知权限被拒时的 App 内兜底预警（PRD §11.4）
                    text = if (warningLevel >= 2) "已用完本月预算" else "已用掉预算的 80%",
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun AccountsCard(
    accounts: List<Account>,
    totalBalanceCents: Long,
    onOpenAccounts: () -> Unit,
) {
    SectionCard(title = "账户", trailing = { TextButton(onClick = onOpenAccounts) { Text("管理") } }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "总余额",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Fmt.money(totalBalanceCents),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (accounts.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "还没有账户。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(accounts, key = { it.accountId }) { account ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleIconBadge(
                            icon = LedgerIcons.accountType(account.type),
                            tint = if (account.isArchived) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            size = 30.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = account.name + if (account.isArchived) "（已归档）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = Fmt.money(account.currentBalance),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (account.currentBalance < 0) {
                                    LedgerSemanticColors.Expense
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
