package com.ledger.android.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledger.android.di.KoinViewModelFactory
import com.ledger.android.di.inject
import com.ledger.domain.error.DomainError
import com.ledger.domain.usecase.ExportDataUseCase
import com.ledger.domain.usecase.ExportOutcome
import com.ledger.domain.usecase.ImportDataUseCase
import com.ledger.domain.usecase.ImportMode
import com.ledger.domain.usecase.ImportPreview
import com.ledger.domain.usecase.ImportPreviewOutcome
import com.ledger.domain.usecase.ImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** P13 需要弹出的对话框（同一时刻最多一个）。 */
sealed interface DataDialog {
    /** 首次导出：设置主密码（≥ 8 位 + 二次确认，PRD §11.6） */
    data object SetupPassword : DataDialog

    /** 导出加密备份：输入主密码 */
    data object ExportPassword : DataDialog

    /** 导入恢复：输入备份文件的主密码（下一步才选文件） */
    data object ImportPassword : DataDialog

    /** 导入预览：覆盖 / 合并二选一（PRD §7.6.2 第 6 步） */
    data class ImportChoice(val preview: ImportPreview) : DataDialog
}

data class DataUiState(
    val masterPasswordSet: Boolean = false,
    val busy: Boolean = false,
    val dialog: DataDialog? = null,
    val message: String? = null,
    val lastExportName: String? = null,
    val lastExportSize: Int = 0,
    val lastImport: ImportResult? = null,
)

/**
 * P13 数据导入导出（PRD §7.6 / §11.6，T23–T25）。
 *
 * 全流程的错误处理遵循 PRD 的硬约束：**任何失败都不写入本地、不破坏现有数据**。
 * 导出与导入的两段式（先解密预览 → 再确认落库）由 `ImportDataUseCase` 保证，
 * 这里只做对话框编排。
 */
class DataViewModel(
    private val exportData: ExportDataUseCase,
    private val importData: ImportDataUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(masterPasswordSet = exportData.isMasterPasswordSet()) }
    }

    /** 备份文件名预览（`ledger-backup-20261009-2130.enc`）。 */
    fun suggestedBackupName(): String = exportData.suggestedBackupFileName()

    fun onExportCsvClicked() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            handleExport(exportData.exportCsv())
        }
    }

    fun onExportBackupClicked() {
        // 首次导出先引导设置主密码（PRD §11.6）
        val dialog = if (exportData.isMasterPasswordSet()) {
            DataDialog.ExportPassword
        } else {
            DataDialog.SetupPassword
        }
        _state.update { it.copy(dialog = dialog) }
    }

    fun onImportClicked() {
        // 先收密码再选文件（`ImportDataUseCase.readAndDecrypt(password)` 的顺序如此），
        // 界面上会明确提示「下一步选择文件」。
        _state.update { it.copy(dialog = DataDialog.ImportPassword) }
    }

    fun dismissDialog() {
        _state.update { it.copy(dialog = null) }
        // 用户取消导入 → 丢弃已解密的待导入数据（内存里不残留明文）
        importData.discard()
    }

    /** 设置主密码（首次导出）。失败（太短 / 两次不一致）由领域层给出中文提示。 */
    fun setupMasterPassword(password: String, confirmation: String, thenExport: Boolean = true) {
        try {
            exportData.setMasterPassword(password, confirmation)
            _state.update {
                it.copy(
                    masterPasswordSet = true,
                    dialog = if (thenExport) DataDialog.ExportPassword else null,
                    message = "主密码已设置，请妥善保管——它无法找回。",
                )
            }
        } catch (t: Throwable) {
            _state.update {
                it.copy(message = (t as? DomainError)?.message ?: t.message ?: "设置主密码失败")
            }
        }
    }

    fun exportBackup(password: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, dialog = null) }
            handleExport(exportData.exportBackup(password))
        }
    }

    private fun handleExport(outcome: ExportOutcome) {
        when (outcome) {
            is ExportOutcome.Success -> _state.update {
                it.copy(
                    busy = false,
                    dialog = null,
                    lastExportName = outcome.result.fileName,
                    lastExportSize = outcome.result.byteSize,
                    message = "已导出 ${outcome.result.fileName}",
                )
            }

            ExportOutcome.Cancelled -> _state.update {
                it.copy(busy = false, dialog = null, message = "已取消导出")
            }

            is ExportOutcome.Failed -> _state.update {
                it.copy(
                    busy = false,
                    dialog = null,
                    message = (outcome.error as? DomainError)?.message
                        ?: outcome.error.message
                        ?: "导出失败",
                )
            }
        }
    }

    /** 导入第 1 段：选文件 → 解密 → 校验 → 出预览（此阶段不写库）。 */
    fun readAndDecrypt(password: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, dialog = null) }
            when (val outcome = importData.readAndDecrypt(password)) {
                is ImportPreviewOutcome.Success -> _state.update {
                    it.copy(
                        busy = false,
                        dialog = DataDialog.ImportChoice(outcome.preview),
                    )
                }

                ImportPreviewOutcome.Cancelled -> _state.update {
                    it.copy(busy = false, message = "已取消导入")
                }

                is ImportPreviewOutcome.Failed -> _state.update {
                    it.copy(
                        busy = false,
                        message = (outcome.error as? DomainError)?.message
                            ?: outcome.error.message
                            ?: "导入失败，本地数据未改动",
                    )
                }
            }
        }
    }

    /** 导入第 2 段：用户确认「覆盖 / 合并」后落库（单事务，失败回滚）。 */
    fun applyImport(mode: ImportMode, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, dialog = null) }
            try {
                val result = importData.apply(mode, password)
                _state.update {
                    it.copy(
                        busy = false,
                        lastImport = result,
                        message = "导入完成：新增 ${result.inserted} 条，跳过 ${result.skipped} 条",
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = (t as? DomainError)?.message
                            ?: t.message
                            ?: "导入失败，本地数据未改动",
                    )
                }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun factory() = KoinViewModelFactory {
            DataViewModel(
                exportData = inject(),
                importData = inject(),
            )
        }
    }
}
