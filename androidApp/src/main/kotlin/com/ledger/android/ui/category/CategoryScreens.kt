package com.ledger.android.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.ui.components.CircleIconBadge
import com.ledger.android.ui.components.ConfirmDialog
import com.ledger.android.ui.components.DragHandle
import com.ledger.android.ui.components.DragReorderColumn
import com.ledger.android.ui.components.EmptyState
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.LoadingState
import com.ledger.android.ui.components.SectionCard
import com.ledger.android.ui.format.parseHexColor
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.domain.model.Category
import com.ledger.domain.model.CategoryKind

// ============================================================ P05 列表

/** P05 分类管理（PRD §4.2 / §11.2）。 */
@Composable
fun CategoryListScreen(
    onBack: () -> Unit,
    onCreateCategory: (CategoryKind) -> Unit,
    onEditCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: CategoryListViewModel = viewModel(factory = CategoryListViewModel.factory())
    val state by vm.state.collectAsState()
    var createForKind by remember { mutableStateOf<CategoryKind?>(null) }

    LedgerScreenScaffold(
        modifier = modifier,
        title = "分类管理",
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { createForKind = CategoryKind.EXPENSE },
                icon = { Icon(LedgerIcons.Add, contentDescription = null) },
                text = { Text("新增分类") },
            )
        },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "长按任意一行可拖动排序。归档后不再出现在记账页，历史流水的分类名不变。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            CategoryGroup(
                title = "支出分类",
                items = state.expense,
                onReorder = vm::reorder,
                onEdit = onEditCategory,
                onToggleArchive = { id, archived -> vm.setArchived(id, archived) },
                onAdd = { createForKind = CategoryKind.EXPENSE },
            )

            Spacer(Modifier.height(20.dp))

            CategoryGroup(
                title = "收入分类",
                items = state.income,
                onReorder = vm::reorder,
                onEdit = onEditCategory,
                onToggleArchive = { id, archived -> vm.setArchived(id, archived) },
                onAdd = { createForKind = CategoryKind.INCOME },
            )

            Spacer(Modifier.height(96.dp))
        }
    }

    createForKind?.let { kind ->
        CategoryKindDialog(
            onDismiss = { createForKind = null },
            onPick = { picked ->
                createForKind = null
                onCreateCategory(picked)
            },
            initial = kind,
        )
    }
}

/** 新增分类时先问「支出还是收入」——kind 决定它能挂到哪类流水上（R-CAT-04）。 */
@Composable
private fun CategoryKindDialog(
    initial: CategoryKind,
    onDismiss: () -> Unit,
    onPick: (CategoryKind) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增哪一类？") },
        text = {
            Column {
                CategoryKind.entries.forEach { kind ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = kind }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == kind,
                            onClick = { selected = kind },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(kind.label + "分类")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected) }) { Text("下一步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CategoryGroup(
    title: String,
    items: List<Category>,
    onReorder: (List<Category>) -> Unit,
    onEdit: (String) -> Unit,
    onToggleArchive: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
) {
    SectionCard(
        title = title,
        trailing = { TextButton(onClick = onAdd) { Text("新增") } },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        if (items.isEmpty()) {
            EmptyState(text = "这一组还没有分类。")
            return@SectionCard
        }
        DragReorderColumn(
            items = items,
            itemHeight = 60.dp,
            keyOf = { it.categoryId },
            onReorder = onReorder,
        ) { category, elevated ->
            CategoryRow(
                category = category,
                elevated = elevated,
                onClick = { onEdit(category.categoryId) },
                onToggleArchive = { onToggleArchive(category.categoryId, !category.isArchived) },
            )
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    elevated: Boolean,
    onClick: () -> Unit,
    onToggleArchive: () -> Unit,
) {
    val color = parseHexColor(category.color, MaterialTheme.colorScheme.primary)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (elevated) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconBadge(
                icon = LedgerIcons.category(category.icon),
                tint = color,
                size = 34.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val tag = buildList {
                    if (category.isSystem) add("预置")
                    if (category.isArchived) add("已归档")
                }
                if (tag.isNotEmpty()) {
                    Text(
                        text = tag.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onToggleArchive) {
                Icon(
                    imageVector = if (category.isArchived) LedgerIcons.Unarchive else LedgerIcons.Archive,
                    contentDescription = if (category.isArchived) "取消归档" else "归档",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DragHandle(modifier = Modifier.size(20.dp))
        }
    }
}

// ============================================================ P06 新增 / 编辑

/** P06 分类编辑（PRD §4.2：名称、图标、颜色，实时预览）。 */
@Composable
fun CategoryEditScreen(
    categoryId: String?,
    onCreateForKind: CategoryKind?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: CategoryEditViewModel = viewModel(
        key = "category-edit-${categoryId ?: "new"}",
        factory = CategoryEditViewModel.factory(categoryId),
    )
    val state by vm.state.collectAsState()

    var pendingMigration by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.finished) {
        if (state.finished) onBack()
    }

    // 新建入口带了 kind，ViewModel 加载完后同步过去
    LaunchedEffect(state.loading, onCreateForKind) {
        if (!state.loading && !state.isEdit && onCreateForKind != null) {
            vm.setKind(onCreateForKind)
        }
    }

    LedgerScreenScaffold(
        modifier = modifier,
        title = if (state.isEdit) "编辑分类" else "新增分类",
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
        actions = {
            if (state.canDelete) {
                IconButton(onClick = { pendingDelete = true }) {
                    Icon(
                        LedgerIcons.Delete,
                        contentDescription = "删除分类",
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 实时预览
            SectionCard(title = "预览") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleIconBadge(
                        icon = LedgerIcons.category(state.icon),
                        tint = parseHexColor(state.color, MaterialTheme.colorScheme.primary),
                        size = 44.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = state.name.ifBlank { "未命名分类" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = state.kind.label + "分类" + if (state.isArchived) " · 已归档" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.name,
                onValueChange = vm::setName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分类名称") },
                singleLine = true,
                supportingText = { Text("同类型下不能重名") },
            )

            if (!state.isEdit) {
                SectionCard(title = "收支类型") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryKind.entries.forEach { kind ->
                            FilterChip(
                                selected = state.kind == kind,
                                onClick = { vm.setKind(kind) },
                                label = { Text(kind.label) },
                            )
                        }
                    }
                }
            }

            SectionCard(title = "图标") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LedgerIcons.categoryPresets.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (name, icon) ->
                                IconChoice(
                                    icon = icon,
                                    selected = state.icon == name,
                                    tint = parseHexColor(state.color, MaterialTheme.colorScheme.primary),
                                    onClick = { vm.setIcon(name) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            SectionCard(title = "颜色") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(LedgerIcons.colorPresets) { hex ->
                        val color = parseHexColor(hex, MaterialTheme.colorScheme.primary)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .then(
                                    if (state.color == hex) {
                                        Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { vm.setColor(hex) },
                        )
                    }
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

            if (state.isEdit) {
                OutlinedButton(
                    onClick = vm::toggleArchived,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.isArchived) "取消归档" else "归档该分类")
                }

                if (state.isSystem) {
                    Text(
                        text = "预置分类不可删除，只能归档（PRD §11.2）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.referencedCount > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "该分类已被 ${state.referencedCount} 笔流水使用。" +
                            "删除前必须为这些流水指定新的分类（PRD §11.2 第 4 条）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 删除：有引用先选迁移目标
    if (pendingDelete) {
        if (state.referencedCount > 0) {
            MigrationTargetDialog(
                candidates = state.migrationCandidates,
                onDismiss = { pendingDelete = false },
                onPick = { targetId ->
                    pendingDelete = false
                    vm.delete(targetId)
                },
            )
        } else {
            ConfirmDialog(
                title = "删除该分类？",
                text = "该分类当前没有流水引用，删除后不可恢复。",
                confirmLabel = "删除",
                destructive = true,
                onConfirm = {
                    pendingDelete = false
                    vm.delete(null)
                },
                onDismiss = { pendingDelete = false },
            )
        }
    }
}

@Composable
private fun IconChoice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 删除分类时的迁移目标选择（PRD §11.2 第 4 条）。 */
@Composable
private fun MigrationTargetDialog(
    candidates: List<Category>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("迁移到哪个分类？") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "历史流水会被改挂到下面选中的分类上，之后再删除原分类。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                candidates.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = category.categoryId }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == category.categoryId,
                            onClick = { selected = category.categoryId },
                        )
                        CircleIconBadge(
                            icon = LedgerIcons.category(category.icon),
                            tint = parseHexColor(category.color, MaterialTheme.colorScheme.primary),
                            size = 28.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(category.name, fontWeight = FontWeight.Normal)
                    }
                }
                if (candidates.isEmpty()) {
                    Text(
                        text = "没有可选的同类分类，请先新建一个同类型的分类。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onPick) },
                enabled = selected != null,
            ) { Text("迁移并删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
