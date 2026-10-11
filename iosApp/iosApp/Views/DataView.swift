import SwiftUI
import Shared

/// P13 数据管理（PRD §4.2 / §7.6 / §11.6）。
///
/// 三条流程都走「系统文件选择器 + 领域层加密」，这一层只负责：
///  1. 该不该先引导设置主密码；
///  2. 把「成功 / 用户取消 / 失败」三态翻译成提示；
///  3. 恢复时让用户在「合并」「覆盖」之间做一次显式选择。
///
/// 不变式（PRD §7.6.3）：**导入的任何失败都不写库**。这不是靠这一层保证的——
/// `ImportDataUseCase` 把流程切成「解密校验」与「落库」两段，第一段一次性读完才返回
/// 预览，第二段在单个事务里完成，任一步失败整体回滚。
struct DataView: View {
    @EnvironmentObject private var env: AppEnvironment
    let onChange: () -> Void

    @State private var busy = false
    @State private var message: String?
    @State private var lastResult: String?

    @State private var hasMasterPassword = false
    @State private var suggestedBackupName = ""

    @State private var showSetupPassword = false
    @State private var showExportPassword = false
    @State private var showImportPassword = false

    /// 导入确认阶段的密码（`apply` 时还要再用一次，不能丢）
    @State private var pendingImportPassword = ""
    @State private var preview: ImportPreview?
    @State private var showImportChoice = false

    @State private var alert: ConfirmSpec?

    var body: some View {
        List {
            if busy {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("处理中…（密钥派生约需数百毫秒）")
                            .font(.subheadline)
                            .foregroundColor(BrandColor.onSurfaceVariant)
                    }
                }
            }

            if let message = message {
                Section { MessageBanner(text: message) }
            }

            Section {
                Button {
                    Task { await exportCsv() }
                } label: {
                    actionRow(
                        icon: "tablecells",
                        title: "导出 CSV",
                        subtitle: "UTF-8 带 BOM，可直接用表格软件打开，不加密"
                    )
                }
                .disabled(busy)

                Button {
                    startExportBackup()
                } label: {
                    actionRow(
                        icon: "lock.doc",
                        title: "导出加密备份",
                        subtitle: hasMasterPassword
                            ? "建议文件名 \(suggestedBackupName)"
                            : "首次使用需先设置主密码"
                    )
                }
                .disabled(busy)
            } header: {
                Text("导出")
            } footer: {
                Text("导出的只是「未删除」的记录；已软删除的流水不会出现在文件里。")
            }

            Section {
                Button {
                    showImportPassword = true
                } label: {
                    actionRow(
                        icon: "arrow.down.doc",
                        title: "导入恢复",
                        subtitle: "选择 .enc 备份文件，输入备份时用的主密码"
                    )
                }
                .disabled(busy)
            } header: {
                Text("恢复")
            } footer: {
                Text("导入分「合并」与「覆盖」两种模式，选定后才真正写库。解密或校验失败时本地数据完全不受影响。")
            }

            if let lastResult = lastResult {
                Section("最近一次结果") {
                    Text(lastResult)
                        .font(.footnote)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                }
            }

            Section {
                PrivacyLine(text: "加密方案：PBKDF2-HMAC-SHA256（600,000 次迭代）+ AES-256-GCM。")
                PrivacyLine(text: "主密码不会保存在任何地方，也无法找回；一旦忘记，加密备份将无法解密。")
                PrivacyLine(text: "数据全程只在本机处理，不经过网络。")
            }
        }
        .listStyle(.insetGrouped)
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("数据管理")
        .navigationBarTitleDisplayMode(.inline)
        .alert(item: $alert) { spec in
            Alert(
                title: Text(spec.title),
                message: Text(spec.message),
                primaryButton: .destructive(Text(spec.confirmLabel), action: spec.onConfirm),
                secondaryButton: .cancel(Text("取消"))
            )
        }
        .sheet(isPresented: $showSetupPassword) {
            PasswordSheet(
                title: "设置主密码",
                message: "备份文件用它加密。密码至少 8 位，请牢记——忘记后无法找回，也无法解密已导出的备份。",
                requireConfirmation: true
            ) { password in
                showSetupPassword = false
                Task { await setupThenExport(password) }
            }
        }
        .sheet(isPresented: $showExportPassword) {
            PasswordSheet(
                title: "输入主密码",
                message: "用同一个主密码加密本次备份。"
            ) { password in
                showExportPassword = false
                Task { await exportBackup(password) }
            }
        }
        .sheet(isPresented: $showImportPassword) {
            PasswordSheet(
                title: "输入备份的主密码",
                message: "接下来会打开文件选择器，请选择当时导出的 .enc 文件。"
            ) { password in
                showImportPassword = false
                Task { await beginImport(password) }
            }
        }
        .sheet(isPresented: $showImportChoice) {
            ImportChoiceSheet(preview: preview) { mode in
                showImportChoice = false
                Task { await applyImport(mode) }
            } onCancel: {
                showImportChoice = false
                // 用户放弃：必须显式丢弃领域层暂存的快照
                env.facade.discardImport()
                pendingImportPassword = ""
                preview = nil
                message = "已取消导入"
            }
        }
        .task { refreshEntryState() }
    }

    private func actionRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundColor(BrandColor.primary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundColor(BrandColor.onSurface)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
                    .lineLimit(2)
            }
            Spacer()
        }
        .contentShape(Rectangle())
    }

    private func refreshEntryState() {
        hasMasterPassword = env.facade.isMasterPasswordSet()
        suggestedBackupName = env.facade.suggestedBackupFileName()
    }

    // ------------------------------------------------------------ 导出

    private func exportCsv() async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let outcome = try await env.facade.exportCsv()
            handleExportOutcome(outcome, label: "CSV")
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func startExportBackup() {
        refreshEntryState()
        if hasMasterPassword {
            showExportPassword = true
        } else {
            showSetupPassword = true
        }
    }

    /// 首次导出：设完主密码直接用同一个密码导出，少一步往返（PRD §11.6 的引导流程）。
    private func setupThenExport(_ password: String) async {
        if let error = env.facade.trySetMasterPassword(
            password: password,
            confirmation: password
        ) {
            message = error
            return
        }
        hasMasterPassword = true
        message = "主密码已设置"
        await exportBackup(password)
    }

    private func exportBackup(_ password: String) async {
        busy = true
        defer { busy = false }
        do {
            let outcome = try await env.facade.exportBackup(password: password)
            handleExportOutcome(outcome, label: "加密备份")
        } catch {
            message = ledgerMessage(error)
        }
        refreshEntryState()
    }

    private func handleExportOutcome(_ outcome: SwiftOutcome, label: String) {
        if outcome.isSuccess {
            message = "已导出 \(outcome.fileName)（\(Fmt.fileSize(outcome.byteSize))）"
            lastResult = "导出\(label)成功：\(outcome.fileName)，\(Fmt.fileSize(outcome.byteSize))"
        } else if outcome.isCancelled {
            message = "已取消导出"
        } else {
            message = outcome.errorMessage ?? "导出失败"
        }
    }

    // ------------------------------------------------------------ 导入

    private func beginImport(_ password: String) async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            let outcome = try await env.facade.importPreview(password: password)
            if outcome.isSuccess, let preview = outcome.preview {
                pendingImportPassword = password
                self.preview = preview
                showImportChoice = true
            } else if outcome.isCancelled {
                message = "已取消导入"
            } else {
                message = outcome.errorMessage ?? "导入失败"
            }
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func applyImport(_ mode: String) async {
        busy = true
        defer { busy = false }
        do {
            let result = try await env.facade.applyImport(
                modeCode: mode,
                password: pendingImportPassword
            )
            let modeLabel = mode == LedgerFacadeKt.importModeReplace() ? "覆盖" : "合并"
            lastResult = "导入\(modeLabel)完成：写入 \(result.inserted) 条，"
                + "跳过 \(result.skipped) 条（共 \(result.totalRecords) 条）"
            message = lastResult
            pendingImportPassword = ""
            preview = nil
            onChange()
        } catch {
            message = ledgerMessage(error)
            pendingImportPassword = ""
            preview = nil
        }
        refreshEntryState()
    }
}

// ============================================================================
// 密码输入
// ============================================================================

struct PasswordSheet: View {
    @Environment(\.dismiss) private var dismiss

    let title: String
    let message: String
    var requireConfirmation = false
    let onSubmit: (String) -> Void

    @State private var password = ""
    @State private var confirmation = ""

    private var mismatch: Bool {
        requireConfirmation && !confirmation.isEmpty && password != confirmation
    }

    private var canSubmit: Bool {
        guard password.count >= 8, !mismatch else { return false }
        return requireConfirmation ? password == confirmation : true
    }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Text(message)
                        .font(.footnote)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                }

                Section("主密码") {
                    SecureField("至少 8 位", text: $password)
                    if requireConfirmation {
                        SecureField("再输一次", text: $confirmation)
                    }
                }

                if mismatch {
                    Section {
                        Text("两次输入不一致")
                            .font(.caption)
                            .foregroundColor(SemanticColor.expense)
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("确定") { onSubmit(password) }
                        .disabled(!canSubmit)
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}

// ============================================================================
// 导入模式选择
// ============================================================================

/// 步骤 6 的确认弹窗：先让用户看清「要导入什么」（各项计数），再问「怎么导」。
///
/// 去重键（PRD §7.6.2）：合并模式按各表主键判断——账户 `account_id`、
/// 分类 `(kind, name)`、流水 `transaction_id`、预算 `(year, month)`、
/// 通知 `(budget_id, threshold, period_key)`、对账 `reconciliation_id`。
struct ImportChoiceSheet: View {
    let preview: ImportPreview?
    let onApply: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationView {
            List {
                Section("将导入的数据") {
                    row("账户", preview?.accountCount ?? 0)
                    row("分类", preview?.categoryCount ?? 0)
                    row("流水", preview?.transactionCount ?? 0)
                    row("预算", preview?.budgetCount ?? 0)
                    row("预算提醒记录", preview?.budgetNotificationCount ?? 0)
                    row("对账记录", preview?.reconciliationCount ?? 0)
                }

                Section {
                    Text("合并：按主键去重，保留本机已有数据；同名但不同 id 的分类会保留为新分类。")
                        .font(.footnote)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                    Text("覆盖：先清空本机账本，再整体写入备份内容。")
                        .font(.footnote)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                } header: {
                    Text("两种模式的区别")
                }

                Section {
                    Button {
                        onApply(LedgerFacadeKt.importModeMerge())
                    } label: {
                        Label("合并导入", systemImage: "arrow.triangle.merge")
                    }
                    Button(role: .destructive) {
                        onApply(LedgerFacadeKt.importModeReplace())
                    } label: {
                        Label("覆盖导入", systemImage: "arrow.clockwise")
                    }
                } footer: {
                    Text("覆盖导入不可撤销。导入过程中任何失败都不会写入本地，现有数据保持不变。")
                }

                if let preview = preview {
                    Section("文件") {
                        row("文件名", preview.fileName)
                        row("大小", Fmt.fileSize(preview.byteSize))
                        row("记录总数", preview.totalRecords)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("确认导入")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", action: onCancel)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func row(_ label: String, _ count: Int32) -> some View {
        HStack {
            Text(label).foregroundColor(BrandColor.onSurface)
            Spacer()
            Text("\(count)")
                .foregroundColor(BrandColor.onSurfaceVariant)
        }
    }

    private func row(_ label: String, _ text: String) -> some View {
        HStack {
            Text(label).foregroundColor(BrandColor.onSurface)
            Spacer()
            Text(text)
                .foregroundColor(BrandColor.onSurfaceVariant)
                .lineLimit(1)
        }
    }
}
