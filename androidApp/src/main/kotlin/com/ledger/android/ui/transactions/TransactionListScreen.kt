package com.ledger.android.ui.transactions

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.ui.components.ConfirmDialog
import com.ledger.android.ui.components.ErrorState
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.LoadingState
import com.ledger.android.ui.components.TransactionRow
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.model.TransactionDetail
import com.ledger.domain.model.TransactionType

/**
 * P12 流水明细页（PRD §4.2）。
 *
 * 同页承担三种入口：首页「查看全部」、报表饼图下钻、报表趋势图下钻。
 * 标题与范围提示由参数推导，避免把中文标题塞进路由。
 */
@Composable
fun TransactionListScreen(
    fromMillis: Long,
    toMillis: Long,
    categoryId: String?,
    type: TransactionType?,
    onBack: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TransactionListViewModel = viewModel(
        key = "txlist-$fromMillis-$toMillis-$categoryId-${type?.dbValue}",
        factory = TransactionListViewModel.factory(fromMillis, toMillis, categoryId, type),
    )
    val state by vm.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<TransactionDetail?>(null) }

    val isAllRange = fromMillis <= 0L && toMillis >= Long.MAX_VALUE - 1L
    val title = when {
        categoryId != null -> "分类明细"
        isAllRange -> "全部流水"
        else -> "流水明细"
    }

    LedgerScreenScaffold(
        modifier = modifier,
        title = title,
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.padding(padding))
            state.error != null -> ErrorState(
                message = state.error!!,
                modifier = Modifier.padding(padding),
                onRetry = vm::load,
            )

            state.groups.isEmpty() -> EmptyHint(
                modifier = Modifier.padding(padding),
                text = "这段时间还没有流水记录。",
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (!isAllRange) {
                    item("range") {
                        Text(
                            text = "统计范围：${Fmt.date(fromMillis)} ~ ${Fmt.date(toMillis - 1)}" +
                                "　共 ${state.totalCount} 笔",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                state.groups.forEach { group ->
                    item("header-${group.label}") {
                        DayHeader(
                            label = group.label,
                            expenseCents = group.expenseCents,
                            incomeCents = group.incomeCents,
                        )
                    }
                    items(group.items, key = { it.id }) { detail ->
                        SwipeableDetailRow(
                            detail = detail,
                            onClick = { onOpenTransaction(detail.id) },
                            onRequestDelete = { pendingDelete = detail },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除这笔流水？",
            text = "删除后余额会一并回滚，且不会进入备份文件（软删除）。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                vm.delete(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayHeader(label: String, expenseCents: Long, incomeCents: Long) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (expenseCents > 0L) {
                Text(
                    text = "支出 " + Fmt.money(expenseCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = LedgerSemanticColors.Expense,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (incomeCents > 0L) {
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "　收入 " + Fmt.money(incomeCents),
                    style = MaterialTheme.typography.bodySmall,
                    color = LedgerSemanticColors.Income,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableDetailRow(
    detail: TransactionDetail,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
) {
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
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                TransactionRow(detail = detail, onClick = onClick, showDate = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Text(
                        text = Fmt.dateTime(detail.transaction.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
