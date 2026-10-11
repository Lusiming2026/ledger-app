package com.ledger.android.ui.record

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.ui.components.CircleIconBadge
import com.ledger.android.ui.components.ConfirmDialog
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.LoadingState
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.format.parseHexColor
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.model.Account
import com.ledger.domain.model.Category
import com.ledger.domain.model.TransactionType
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * P03 快速记账 / P04 编辑（PRD §5.1 / §4.2）。
 *
 * 布局刻意把「金额 + 键盘 + 保存」固定在底部拇指区，其余（类型/分类/账户/备注/日期）
 * 放在上半部可滚动区：用户点进来手指已经在下半屏，输完金额直接按保存即可，
 * 这就是「≤ 3 步」在交互上的落地。
 *
 * 编辑模式下：类型不可改（与 `AddTransactionUseCase.update` 一致），顶部出现删除入口。
 */
@Composable
fun RecordScreen(
    transactionId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: RecordViewModel = viewModel(
        key = "record-${transactionId ?: "new"}",
        factory = RecordViewModel.factory(transactionId),
    )
    val state by vm.state.collectAsState()

    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    LedgerScreenScaffold(
        modifier = modifier,
        title = if (state.isEditMode) "编辑流水" else "记一笔",
        onBack = onDone,
        message = state.message,
        onMessageShown = vm::consumeMessage,
        actions = {
            if (state.isEditMode) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        LedgerIcons.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@LedgerScreenScaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ---------------------------------------------------- 可滚动区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                if (!state.isEditMode) {
                    TypeTabs(selected = state.type, onSelect = vm::setType)
                    Spacer(Modifier.height(12.dp))
                }

                if (state.type != TransactionType.TRANSFER) {
                    CategoryPicker(
                        categories = state.visibleCategories,
                        selectedId = state.categoryId,
                        onSelect = vm::selectCategory,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                AccountPicker(
                    title = when (state.type) {
                        TransactionType.EXPENSE -> "从哪个账户支出"
                        TransactionType.INCOME -> "收到哪个账户"
                        TransactionType.TRANSFER -> "转出账户"
                    },
                    accounts = state.accounts,
                    selectedId = state.fromAccountId,
                    onSelect = vm::selectFromAccount,
                )

                if (state.type == TransactionType.TRANSFER) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountPicker(
                            title = "转入账户",
                            accounts = state.accounts,
                            selectedId = state.toAccountId,
                            onSelect = vm::selectToAccount,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = vm::swapAccounts) {
                            Icon(LedgerIcons.transactionType(TransactionType.TRANSFER), "交换")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.note,
                    onValueChange = vm::setNote,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注（选填）") },
                    placeholder = { Text("午饭、地铁…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    supportingText = { Text("${state.note.length}/50") },
                )

                Spacer(Modifier.height(8.dp))

                DateField(
                    occurredAtMillis = state.occurredAtMillis,
                    onClick = { showDatePicker = true },
                )

                Spacer(Modifier.height(16.dp))
            }

            // ---------------------------------------------------- 固定区：金额 + 键盘 + 保存
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    AmountBar(
                        amountText = state.amountText,
                        type = state.type,
                        onBackspace = vm::backspace,
                        onClear = vm::clearAmount,
                    )
                    AmountKeypad(
                        onDigit = vm::pressDigit,
                        onDot = vm::pressDot,
                        onDoubleZero = vm::pressDoubleZero,
                        onBackspace = vm::backspace,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = vm::save,
                        enabled = state.canSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(
                            text = when {
                                state.saving -> "保存中…"
                                state.isEditMode -> "保存修改"
                                else -> "保存"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        LedgerDatePickerDialog(
            occurredAtMillis = state.occurredAtMillis,
            onDismiss = { showDatePicker = false },
            onPicked = { millis ->
                vm.setOccurredAt(millis)
                showDatePicker = false
            },
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除这笔流水？",
            text = "删除后余额会一并回滚，且不会进入备份文件（软删除）。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                showDeleteConfirm = false
                vm.deleteExisting()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

// ------------------------------------------------------------------ 子组件

@Composable
private fun TypeTabs(selected: TransactionType, onSelect: (TransactionType) -> Unit) {
    val types = listOf(
        TransactionType.EXPENSE,
        TransactionType.INCOME,
        TransactionType.TRANSFER,
    )
    TabRow(
        selectedTabIndex = types.indexOf(selected),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small),
    ) {
        types.forEach { type ->
            Tab(
                selected = type == selected,
                onClick = { onSelect(type) },
                text = { Text(type.label) },
            )
        }
    }
}

@Composable
private fun CategoryPicker(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "分类",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        if (categories.isEmpty()) {
            Text(
                text = "还没有可用分类，去「设置 → 分类管理」新增一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        // 手写网格（chunked）而不是 LazyVerticalGrid：外层是 verticalScroll，
        // 同轴嵌套滚动会因「无限高度」直接抛异常。
        categories.chunked(5).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { category ->
                    CategoryCell(
                        category = category,
                        selected = category.categoryId == selectedId,
                        onClick = { onSelect(category.categoryId) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 补齐最后一行，避免不足 5 个时被拉伸得大小不一
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CategoryCell(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = parseHexColor(category.color, MaterialTheme.colorScheme.primary)
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, color, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        ) {
            CircleIconBadge(
                icon = LedgerIcons.category(category.icon),
                tint = color,
                size = 44.dp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AccountPicker(
    title: String,
    accounts: List<Account>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        if (accounts.isEmpty()) {
            Text(
                text = "还没有可用账户，去「设置 → 账户管理」新建一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(accounts, key = { it.accountId }) { account ->
                FilterChip(
                    selected = account.accountId == selectedId,
                    onClick = { onSelect(account.accountId) },
                    leadingIcon = {
                        Icon(
                            imageVector = LedgerIcons.accountType(account.type),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text("${account.name} ${Fmt.money(account.currentBalance)}") },
                )
            }
        }
    }
}

@Composable
private fun DateField(occurredAtMillis: Long, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = LedgerIcons.Calendar,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "记账日期",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Fmt.date(occurredAtMillis),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 金额条：显示当前输入 + 类型，右侧提供退格与清零（键盘上也可以退格）。 */
@Composable
private fun AmountBar(
    amountText: String,
    type: TransactionType,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val accent = when (type) {
        TransactionType.EXPENSE -> LedgerSemanticColors.Expense
        TransactionType.INCOME -> LedgerSemanticColors.Income
        TransactionType.TRANSFER -> LedgerSemanticColors.Transfer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = type.label,
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = "¥",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = amountText.ifEmpty { "0.00" },
            style = MaterialTheme.typography.displaySmall,
            color = if (amountText.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (amountText.isNotEmpty()) {
            TextButton(onClick = onClear) { Text("清零") }
        }
    }
}

/** 数字键盘。刻意不用系统 IME：记账场景里弹出输入法会顶掉布局，还要多一次「收起」。 */
@Composable
private fun AmountKeypad(
    onDigit: (Char) -> Unit,
    onDot: () -> Unit,
    onDoubleZero: () -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "00"),
    )
    Column {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    KeypadKey(
                        label = key,
                        onClick = {
                            when (key) {
                                "." -> onDot()
                                "00" -> onDoubleZero()
                                else -> onDigit(key[0])
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            KeypadKey(
                label = "⌫ 退格",
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(46.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 日期选择。
 *
 * `DatePickerState.selectedDateMillis` 表示的是**所选那天的 UTC 零点**，
 * 不能直接当成「本地零点」用，否则东八区会整体偏一天。
 * 因此这里把选中值先按 UTC 解出「年月日」，再拼回**本地时区**的同一时刻，
 * 时分沿用原值（用户只是想改日期，不该被悄悄改成 00:00）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerDatePickerDialog(
    occurredAtMillis: Long,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
) {
    val zone = TimeZone.currentSystemDefault()
    val initialUtcDay = remember(occurredAtMillis) {
        val local = Instant.fromEpochMilliseconds(occurredAtMillis).toLocalDateTime(zone)
        LocalDateTime(local.year, local.monthNumber, local.dayOfMonth, 0, 0, 0, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
    }
    val pickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialUtcDay,
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = pickerState.selectedDateMillis
                    onPicked(
                        if (picked == null) occurredAtMillis
                        else mergePickedDate(picked, occurredAtMillis),
                    )
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = pickerState)
    }
}

private fun mergePickedDate(selectedUtcDayMillis: Long, keepMillis: Long): Long {
    val zone = TimeZone.currentSystemDefault()
    val date = Instant.fromEpochMilliseconds(selectedUtcDayMillis)
        .toLocalDateTime(TimeZone.UTC)
        .date
    val keepLocal = Instant.fromEpochMilliseconds(keepMillis).toLocalDateTime(zone)
    return LocalDateTime(
        year = date.year,
        monthNumber = date.monthNumber,
        dayOfMonth = date.dayOfMonth,
        hour = keepLocal.hour,
        minute = keepLocal.minute,
        second = keepLocal.second,
        nanosecond = 0,
    ).toInstant(zone).toEpochMilliseconds()
}
