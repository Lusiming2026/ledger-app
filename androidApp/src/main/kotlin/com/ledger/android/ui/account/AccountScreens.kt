package com.ledger.android.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.ui.components.CircleIconBadge
import com.ledger.android.ui.components.DragHandle
import com.ledger.android.ui.components.DragReorderColumn
import com.ledger.android.ui.components.EmptyState
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.ListRow
import com.ledger.android.ui.components.LoadingState
import com.ledger.android.ui.components.SectionCard
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.model.Account
import com.ledger.domain.model.AccountType
import com.ledger.domain.model.Reconciliation

// ============================================================ P07 列表

/**
 * P07 账户管理（PRD §4.2 / §11.3）。
 *
 * 交互约定：
 *  - 点账户本体 → 进 P09 对账（PRD 明确「点账户进入对账」）；
 *  - 行尾「编辑」→ P08；「归档 / 取消归档」→ 就地切换；
 *  - **长按整行拖动** → 排序，松手立即持久化 `sort_order`。
 */
@Composable
fun AccountListScreen(
    onBack: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onCreateAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: AccountListViewModel = viewModel(factory = AccountListViewModel.factory())
    val state by vm.state.collectAsState()

    LedgerScreenScaffold(
        modifier = modifier,
        title = "账户管理",
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateAccount,
                icon = { Icon(LedgerIcons.Add, contentDescription = null) },
                text = { Text("新增账户") },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.padding(padding))

            state.accounts.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                EmptyState(
                    text = "还没有账户。现金、银行卡、微信、支付宝都可以建。",
                    actionLabel = "新增账户",
                    onAction = onCreateAccount,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "长按任意一行可拖动排序；账户只能归档、不能删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                DragReorderColumn(
                    items = state.accounts,
                    itemHeight = 76.dp,
                    keyOf = { it.accountId },
                    onReorder = vm::reorder,
                ) { account, isDragging ->
                    AccountRow(
                        account = account,
                        elevated = isDragging,
                        onClick = { onOpenAccount(account.accountId) },
                        onEdit = { onEditAccount(account.accountId) },
                        onToggleArchive = { vm.setArchived(account.accountId, !account.isArchived) },
                    )
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    elevated: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (elevated) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconBadge(
                icon = LedgerIcons.accountType(account.type),
                tint = if (account.isArchived) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.primary
                },
                size = 38.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.type.label + if (account.isArchived) " · 已归档" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            IconButton(onClick = onToggleArchive) {
                Icon(
                    imageVector = if (account.isArchived) LedgerIcons.Unarchive else LedgerIcons.Archive,
                    contentDescription = if (account.isArchived) "取消归档" else "归档",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = LedgerIcons.Edit,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DragHandle(modifier = Modifier.size(20.dp))
        }
    }
}

// ============================================================ P08 新增 / 编辑

/** P08 账户编辑（PRD §4.2）。初始余额仅创建时可填，创建后不可改（§11.3）。 */
@Composable
fun AccountEditScreen(
    accountId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: AccountEditViewModel = viewModel(
        key = "account-edit-${accountId ?: "new"}",
        factory = AccountEditViewModel.factory(accountId),
    )
    val state by vm.state.collectAsState()

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    LedgerScreenScaffold(
        modifier = modifier,
        title = if (state.isEdit) "编辑账户" else "新增账户",
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@LedgerScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("账户名称") },
                placeholder = { Text("现金、招行储蓄卡、微信零钱…") },
                singleLine = true,
            )

            SectionCard(title = "账户类型") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AccountType.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { type ->
                                FilterChip(
                                    selected = state.type == type,
                                    onClick = { vm.setType(type) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = LedgerIcons.accountType(type),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                    label = { Text(type.label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.initialBalanceText,
                onValueChange = vm::setInitialBalance,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("初始余额（元）") },
                enabled = !state.isEdit,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    Text(
                        text = if (state.isEdit) {
                            "初始余额创建后不可修改（PRD §11.3）。当前余额由流水自动联动。"
                        } else {
                            "可以是负数，例如信用卡欠款填 -1200。"
                        },
                    )
                },
            )

            if (state.isEdit) {
                SectionCard(title = "当前余额") {
                    Text(
                        text = Fmt.money(state.currentBalanceCents),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "想校准账面余额，请回上一页点这个账户进入「对账」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = vm::save,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (state.saving) "保存中…" else "保存")
            }
        }
    }
}

// ============================================================ P09 对账

/** P09 对账（PRD §4.2 / §6.2.6 / §11.3）。 */
@Composable
fun ReconcileScreen(
    accountId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ReconcileViewModel = viewModel(
        key = "reconcile-$accountId",
        factory = ReconcileViewModel.factory(accountId),
    )
    val state by vm.state.collectAsState()

    // 对账成功后不关闭页面：用户通常想顺手看看「历史」有没有记上
    LedgerScreenScaffold(
        modifier = modifier,
        title = "对账",
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@LedgerScreenScaffold
        }

        val account = state.account
        if (account == null) {
            EmptyState(
                modifier = Modifier.padding(padding),
                text = "账户不存在，可能已被移除。",
            )
            return@LedgerScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = account.name + " · " + account.type.label) {
                Text(
                    text = "当前账面余额",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = Fmt.money(account.currentBalance),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            OutlinedTextField(
                value = state.actualBalanceText,
                onValueChange = vm::setActualBalance,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("实际余额（元）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    val diff = state.differenceCents
                    Text(
                        text = when {
                            diff == null -> "把钱包/银行 App 里看到的真实金额填进来"
                            diff == 0L -> "与账面一致，无需校准"
                            diff > 0L -> "比账面多 " + Fmt.money(diff) + "（可能是漏记支出）"
                            else -> "比账面少 " + Fmt.money(-diff) + "（可能是漏记收入）"
                        },
                        color = if (diff != null && diff != 0L) {
                            LedgerSemanticColors.Expense
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = vm::setNote,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("备注（选填）") },
                singleLine = true,
            )

            Button(
                onClick = vm::save,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (state.saving) "校准中…" else "按实际余额校准")
            }

            Text(
                text = "对账只校准账面余额并留痕，不会生成差额调整流水（PRD §6.2.6）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(title = "对账历史") {
                if (state.history.isEmpty()) {
                    Text(
                        text = "还没有对账记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.history.forEachIndexed { index, record ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        ReconciliationRow(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReconciliationRow(record: Reconciliation) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = Fmt.dateTime(record.reconciledAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val note = record.note
            if (!note.isNullOrBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = Fmt.money(record.balance),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
