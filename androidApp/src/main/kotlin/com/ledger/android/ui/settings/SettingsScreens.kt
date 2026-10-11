package com.ledger.android.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ledger.android.BuildConfig
import com.ledger.android.ui.LocalAppUiState
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.SectionCard
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.android.ui.theme.ThemeMode
import com.ledger.domain.port.NotificationSchedulerPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ============================================================ 设置主页（P15 入口）

data class SettingsUiState(
    val notificationsAllowed: Boolean = true,
)

class SettingsViewModel(
    private val notificationScheduler: NotificationSchedulerPort,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = SettingsUiState(
                notificationsAllowed = notificationScheduler.hasPermission(),
            )
        }
    }

    companion object {
        fun factory() = KoinViewModelFactory {
            SettingsViewModel(notificationScheduler = inject())
        }
    }
}

/** 设置 Tab（PRD §4.1 信息架构）。 */
@Composable
fun SettingsScreen(
    onOpenCategories: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory())
    val state by vm.state.collectAsState()

    LedgerScreenScaffold(
        modifier = modifier,
        title = "设置",
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = "记账配置") {
                SettingRow(LedgerIcons.ListIcon, "分类管理", "新增 / 编辑 / 排序 / 归档分类", onOpenCategories)
                Divider()
                SettingRow(LedgerIcons.Settings, "账户管理", "现金 / 银行卡 / 微信 / 支付宝，支持对账", onOpenAccounts)
                Divider()
                SettingRow(
                    icon = LedgerIcons.Report,
                    title = "预算",
                    subtitle = "月度总预算与超支提醒",
                    onClick = onOpenBudget,
                )
            }

            SectionCard(title = "数据") {
                SettingRow(LedgerIcons.Export, "数据导入 / 导出", "CSV 导出、加密备份、恢复", onOpenData)
            }

            SectionCard(title = "通用") {
                SettingRow(LedgerIcons.Appearance, "外观", "深色模式：跟随系统 / 浅色 / 深色", onOpenAppearance)
                Divider()
                SettingRow(
                    icon = LedgerIcons.Info,
                    title = "关于与隐私说明",
                    subtitle = "版本、权限、开源声明",
                    onClick = onOpenAbout,
                )
            }

            SectionCard(title = "通知") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.notificationsAllowed) {
                            LedgerIcons.Success
                        } else {
                            LedgerIcons.Warning
                        },
                        contentDescription = null,
                        tint = if (state.notificationsAllowed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LedgerSemanticColors.BudgetWarning
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (state.notificationsAllowed) {
                            "预算提醒通知已开启"
                        } else {
                            "通知权限未开启：到「系统设置 → 应用 → 记账 → 通知」里开启后，" +
                                "预算达到 80% / 100% 才会推送。未开启时进度条仍会变色预警。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================ P14 外观

/** P14 外观设置（PRD §4.2 P14 / §11.7：切换即时生效）。 */
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appUiState = LocalAppUiState.current

    LedgerScreenScaffold(
        modifier = modifier,
        title = "外观",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SectionCard(title = "深色模式", contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色"
                        ThemeMode.DARK -> "深色"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { appUiState.updateThemeMode(mode) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = appUiState.themeMode == mode,
                            onClick = { appUiState.updateThemeMode(mode) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (mode == ThemeMode.SYSTEM) {
                                Text(
                                    text = "跟随系统的深色模式开关自动切换",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "选择后立即生效，全部页面（含图表配色）都会跟随切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================ P15 关于

/** P15 关于 / 隐私说明（PRD §4.2 P15）。 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerScreenScaffold(
        modifier = modifier,
        title = "关于",
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "记账",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "3 步记一笔，账本自己算",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME}（MVP）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }

            SectionCard(title = "隐私说明") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrivacyLine("数据只存在本机，数据库以 SQLCipher 加密，密钥存于系统安全区。")
                    PrivacyLine("不申请网络权限，飞行模式下记账、报表、预算全部可用。")
                    PrivacyLine("不申请存储权限，导入导出通过系统文件选择器完成。")
                    PrivacyLine("不采集任何设备标识、位置、通讯录、照片等信息。")
                    PrivacyLine("云备份与换机迁移对账本数据已关闭。")
                }
            }

            SectionCard(title = "加密方案") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrivacyLine("数据库：SQLCipher，随机 256 位密钥，存 Keychain / Keystore。")
                    PrivacyLine("备份文件：PBKDF2-HMAC-SHA256（600,000 次迭代，16 字节盐）派生密钥。")
                    PrivacyLine("加密：AES-256-GCM，12 字节随机 nonce，16 字节认证标签。")
                    PrivacyLine("派生用的密钥在用完后立即清零，不落盘。")
                }
            }

            SectionCard(title = "开源声明") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrivacyLine("Kotlin Multiplatform / kotlinx.coroutines / kotlinx.serialization / kotlinx-datetime")
                    PrivacyLine("SQLDelight — 跨平台 SQL 代码生成（Apache-2.0）")
                    PrivacyLine("SQLCipher — SQLite 加密扩展（BSD-style）")
                    PrivacyLine("Koin — 依赖注入（Apache-2.0）")
                    PrivacyLine("Jetpack Compose / Material 3（Apache-2.0）")
                }
            }

            SectionCard(title = "备份恢复须知") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrivacyLine("主密码不会保存在任何地方，忘记后备份文件无法恢复。")
                    PrivacyLine("已软删除的流水不进入备份，恢复后也不会复活。")
                    PrivacyLine("覆盖模式会清空本地账本；合并模式按去重键追加，不会覆盖本地已有同 ID 数据。")
                }
            }
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
