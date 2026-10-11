package com.ledger.android.ui.data

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.SectionCard
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.usecase.ImportMode
import com.ledger.domain.usecase.ImportPreview

/**
 * P13 数据导入导出（PRD §7.6 / §11.6）。
 *
 * 三个入口：
 *  1. **导出 CSV**（UTF-8 BOM，Excel 可直接打开）；
 *  2. **导出加密备份**（主密码 → PBKDF2 600k → AES-256-GCM → `.enc`）；
 *  3. **导入恢复**（选 `.enc` → 主密码 → 预览 → 覆盖 / 合并）。
 */
@Composable
fun DataScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: DataViewModel = viewModel(factory = DataViewModel.factory())
    val state by vm.state.collectAsState()

    // 导入分两段（解密预览 → 确认落库），密码要在第二段复用，因此留在屏幕层级
    var importPassword by remember { mutableStateOf("") }

    LedgerScreenScaffold(
        modifier = modifier,
        title = "数据导入 / 导出",
        onBack = onBack,
        message = state.message,
        onMessageShown = vm::consumeMessage,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "导出") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = vm::onExportCsvClicked,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(LedgerIcons.Export, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("导出 CSV（当前账本全部流水）")
                    }

                    OutlinedButton(
                        onClick = vm::onExportBackupClicked,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(LedgerIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("导出加密备份（.enc）")
                    }

                    Text(
                        text = "建议文件名：" + vm.suggestedBackupName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.masterPasswordSet) {
                        Text(
                            text = "主密码已设置。备份文件用 AES-256-GCM 加密，上传到网盘也只是密文。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "首次导出会引导你设置主密码（至少 8 位，不可找回）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LedgerSemanticColors.BudgetWarning,
                        )
                    }
                }
            }

            SectionCard(title = "导入恢复") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = vm::onImportClicked,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(LedgerIcons.Import, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("选择 .enc 备份文件导入")
                    }
                    Text(
                        text = "导入分两步：先解密并校验（此阶段不写任何数据），" +
                            "再由你选择「覆盖」或「合并」。任何失败都不会破坏现有数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.lastExportName != null) {
                SectionCard(title = "最近一次导出") {
                    Text(
                        text = state.lastExportName!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "大小 " + Fmt.fileSize(state.lastExportSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.lastImport?.let { result ->
                SectionCard(title = "最近一次导入") {
                    Text(
                        text = when (result.mode) {
                            ImportMode.REPLACE -> "模式：覆盖"
                            ImportMode.MERGE -> "模式：合并"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "共 ${result.totalRecords} 条，新增 ${result.inserted} 条，跳过 ${result.skipped} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // PBKDF2 600k 迭代需要几百毫秒，明确告知而不是让用户以为卡死（PRD §11.6）
                        text = "处理中…（密钥派生约需数百毫秒）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionCard(title = "隐私说明") {
                Column {
                    Text(
                        text = "· 全部数据只存在这台设备上，数据库用 SQLCipher 加密，" +
                            "密钥随机生成后存进系统安全区（Keystore）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "· 应用不申请网络、存储、位置、通讯录等任何权限，飞行模式下功能完整可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "· 云备份与换机迁移已关闭，账本不会随系统备份上云。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        DataDialog.SetupPassword -> SetupPasswordDialog(
            onDismiss = vm::dismissDialog,
            onConfirm = { password, confirm -> vm.setupMasterPassword(password, confirm) },
        )

        DataDialog.ExportPassword -> PasswordDialog(
            title = "输入主密码",
            hint = "备份文件将用它加密，导入时需要同一个密码。",
            onDismiss = vm::dismissDialog,
            onConfirm = vm::exportBackup,
        )

        DataDialog.ImportPassword -> PasswordDialog(
            title = "输入备份文件的主密码",
            hint = "确认后将打开系统文件选择器，请选中要导入的 .enc 文件。",
            onDismiss = vm::dismissDialog,
            onConfirm = { password ->
                importPassword = password
                vm.readAndDecrypt(password)
            },
        )

        is DataDialog.ImportChoice -> ImportChoiceDialog(
            preview = dialog.preview,
            onDismiss = vm::dismissDialog,
            onReplace = { vm.applyImport(ImportMode.REPLACE, importPassword) },
            onMerge = { vm.applyImport(ImportMode.MERGE, importPassword) },
        )

        null -> Unit
    }
}

// ------------------------------------------------------------------ 对话框

@Composable
private fun SetupPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < 8
    val mismatch = confirmation.isNotEmpty() && confirmation != password

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置主密码") },
        text = {
            Column {
                Text(
                    text = "主密码用于加密备份文件。它**不会被保存在任何地方**，" +
                        "一旦忘记，备份文件将无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LedgerSemanticColors.BudgetWarning,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("主密码（至少 8 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = tooShort,
                    supportingText = { if (tooShort) Text("至少 8 位") },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("再次输入") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("两次输入不一致") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password, confirmation) },
                enabled = password.length >= 8 && password == confirmation,
            ) { Text("设置并继续") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PasswordDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("主密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ImportChoiceDialog(
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onReplace: () -> Unit,
    onMerge: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入方式") },
        text = {
            Column {
                Text(
                    text = preview.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = Fmt.fileSize(preview.byteSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PreviewLine("账户", preview.accountCount)
                PreviewLine("分类", preview.categoryCount)
                PreviewLine("流水", preview.transactionCount)
                PreviewLine("预算", preview.budgetCount)
                PreviewLine("预算提醒记录", preview.budgetNotificationCount)
                PreviewLine("对账记录", preview.reconciliationCount)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "「覆盖」会清空本地账本后整体导入；" +
                        "「合并」按主键去重后追加，本地已有同 ID 的记录会跳过" +
                        "（预算按年月、分类按类型+名称去重）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onMerge) { Text("合并") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onReplace) { Text("覆盖") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PreviewLine(label: String, count: Int) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
