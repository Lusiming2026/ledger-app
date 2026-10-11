import SwiftUI
import Shared

// ============================================================================
// P07 账户管理（PRD §4.2 / §11.3）
// ============================================================================

/// 账户列表。三条硬约束来自 PRD §6.2.1 / §11.3：
///  - 账户**只能归档、不能删除**（历史流水要保住原账户）；
///  - 初始余额**创建后不可修改**（想改就走去对账）；
///  - 支持手动排序，记账页按 `sort_order` 展示。
struct AccountListScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    let onChange: () -> Void

    @State private var accounts: [Account] = []
    @State private var loading = true
    @State private var message: String?
    @State private var subscription: Subscription?
    @State private var editTarget: AccountTarget?

    private var totalBalanceCents: Int64 {
        accounts.reduce(Int64(0)) { $0 + $1.currentBalance }
    }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    if let message = message {
                        Section {
                            MessageBanner(text: message)
                                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 0, trailing: 16))
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        }
                    }

                    Section {
                        HStack {
                            Text("总余额")
                                .font(.subheadline)
                                .foregroundColor(BrandColor.onSurfaceVariant)
                            Spacer()
                            Text(Fmt.money(totalBalanceCents))
                                .font(.title3.weight(.semibold))
                                .foregroundColor(totalBalanceCents < 0
                                                 ? SemanticColor.expense
                                                 : BrandColor.onSurface)
                        }
                    } footer: {
                        Text("总余额含已归档账户。归档只影响记账时的选择，不影响历史统计。")
                    }

                    Section("账户") {
                        if accounts.isEmpty {
                            Text("还没有账户").font(.caption)
                        } else {
                            ForEach(accounts, id: \.accountId) { account in
                                NavigationLink {
                                    ReconcileScreen(accountId: account.accountId) { onChange() }
                                        .environmentObject(env)
                                } label: {
                                    AccountRow(account: account)
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    Button {
                                        Task {
                                            await setArchived(account, archived: !account.isArchived)
                                        }
                                    } label: {
                                        Label(
                                            account.isArchived ? "取消归档" : "归档",
                                            systemImage: account.isArchived
                                                ? "tray.and.arrow.up"
                                                : "archivebox"
                                        )
                                    }
                                    .tint(BrandColor.warningTint)

                                    Button {
                                        editTarget = AccountTarget(accountId: account.accountId)
                                    } label: {
                                        Label("编辑", systemImage: "pencil")
                                    }
                                    .tint(BrandColor.primary)
                                }
                            }
                            .onMove { indices, newOffset in
                                var reordered = accounts
                                reordered.move(fromOffsets: indices, toOffset: newOffset)
                                Task { await applyReorder(reordered) }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("账户管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) { EditButton() }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    editTarget = AccountTarget(accountId: nil)
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(item: $editTarget) { target in
            AccountEditScreen(accountId: target.accountId) {
                editTarget = nil
                onChange()
            }
            .environmentObject(env)
        }
        .onAppear(perform: subscribe)
        .onDisappear { subscription?.cancel(); subscription = nil }
    }

    private func subscribe() {
        guard subscription == nil else { return }
        subscription = env.facade.observeAccounts { value in
            accounts = value
            loading = false
        }
    }

    private func setArchived(_ account: Account, archived: Bool) async {
        do {
            try await env.facade.setAccountArchived(accountId: account.accountId, archived: archived)
            message = archived ? "已归档，记账时不再出现" : "已取消归档"
            onChange()
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func applyReorder(_ ordered: [Account]) async {
        do {
            try await env.facade.reorderAccounts(orderedIds: ordered.map { $0.accountId })
        } catch {
            message = ledgerMessage(error)
        }
    }
}

struct AccountRow: View {
    let account: Account

    var body: some View {
        HStack(spacing: 12) {
            IconBadge(
                systemName: LedgerIcon.accountSymbol(typeCode: account.type.dbValue),
                tint: account.isArchived ? BrandColor.outline : BrandColor.primary,
                size: 34
            )
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(account.name)
                        .font(.subheadline)
                        .foregroundColor(BrandColor.onSurface)
                    if account.isArchived {
                        Tag(text: "已归档", tint: BrandColor.warningTint)
                    }
                }
                Text(IosUiBridgeKt.accountTypeLabel(code: account.type.dbValue))
                    .font(.caption2)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            }
            Spacer()
            Text(Fmt.money(account.currentBalance))
                .font(.subheadline.weight(.medium))
                .foregroundColor(account.currentBalance < 0
                                 ? SemanticColor.expense
                                 : BrandColor.onSurface)
        }
        .padding(.vertical, 6)
    }
}

struct AccountTarget: Identifiable {
    let id = UUID()
    /// nil = 新建
    let accountId: String?
}

// ============================================================================
// P08 账户新建 / 编辑（PRD §11.3）
// ============================================================================

struct AccountEditScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    /// nil = 新建
    let accountId: String?
    let onDone: () -> Void

    @State private var loading = true
    @State private var saving = false
    @State private var message: String?

    @State private var name = ""
    @State private var typeCode = "cash"
    /// 元（字符串），仅在新建时可编辑
    @State private var initialBalanceText = "0"
    @State private var currentBalanceCents: Int64 = 0

    private var isEdit: Bool { accountId != nil }

    private var initialBalanceCents: Int64? { Fmt.parseAmount(initialBalanceText) }

    private var canSave: Bool {
        guard !name.trimmingCharacters(in: .whitespaces).isEmpty, !saving else { return false }
        // 编辑模式不校验初始余额（它不可改，只是个只读展示）
        return isEdit || initialBalanceCents != nil
    }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Form {
                    if let message = message {
                        Section { MessageBanner(text: message) }
                    }

                    Section("名称") {
                        TextField("比如「招行储蓄卡」", text: nameBinding)
                    }

                    Section("类型") {
                        Picker("类型", selection: $typeCode) {
                            ForEach(IosUiBridgeKt.accountTypeOptions(), id: \.code) { option in
                                Text(option.label).tag(option.code)
                            }
                        }
                        .pickerStyle(.menu)
                    }

                    if isEdit {
                        Section {
                            HStack {
                                Text("当前余额")
                                Spacer()
                                Text(Fmt.money(currentBalanceCents))
                                    .foregroundColor(currentBalanceCents < 0
                                                     ? SemanticColor.expense
                                                     : BrandColor.onSurface)
                            }
                            HStack {
                                Text("初始余额")
                                Spacer()
                                Text(Fmt.money(initialBalanceCents ?? 0))
                                    .foregroundColor(BrandColor.onSurfaceVariant)
                            }
                        } footer: {
                            Text("初始余额创建后不可修改（PRD §11.3）。如果账面与实际不符，请退回上一层进入「对账」。")
                        }
                    } else {
                        Section {
                            HStack {
                                Text("初始余额")
                                Spacer()
                                TextField("0", text: initialBalanceBinding)
                                    .multilineTextAlignment(.trailing)
                                    .keyboardType(.numbersAndPunctuation)
                                    .frame(maxWidth: 140)
                            }
                        } footer: {
                            Text("新账户的当前余额 = 初始余额。可以是负数，例如信用卡欠款。")
                        }
                    }
                }
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle(isEdit ? "编辑账户" : "新建账户")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("保存") { Task { await save() } }
                    .disabled(!canSave)
            }
        }
        .task { await load() }
    }

    private var nameBinding: Binding<String> {
        Binding(get: { name }, set: { name = String($0.prefix(12)) })
    }

    /// 只允许数字、小数点和负号（初始余额可为负）
    private var initialBalanceBinding: Binding<String> {
        Binding(
            get: { initialBalanceText },
            set: { if isNumericInput($0) { initialBalanceText = $0 } }
        )
    }

    private func load() async {
        defer { loading = false }
        if let id = accountId {
            do {
                guard let existing = try await env.facade.accountById(accountId: id) else {
                    message = "账户不存在"
                    return
                }
                name = existing.name
                typeCode = existing.type.dbValue
                initialBalanceText = Fmt.plainYuan(existing.initialBalance)
                currentBalanceCents = existing.currentBalance
            } catch {
                message = ledgerMessage(error)
            }
        }
    }

    private func save() async {
        guard canSave else { return }
        saving = true
        defer { saving = false }
        do {
            if let id = accountId {
                _ = try await env.facade.updateAccount(
                    accountId: id,
                    name: name,
                    typeCode: typeCode
                )
            } else {
                _ = try await env.facade.createAccount(
                    accountId: Fmt.newAccountId(),
                    name: name,
                    typeCode: typeCode,
                    initialBalanceCents: initialBalanceCents ?? 0
                )
            }
            onDone()
        } catch {
            message = ledgerMessage(error)
        }
    }
}

// ============================================================================
// P09 对账（PRD §6.2.6 / §11.3）
// ============================================================================

/// 对账语义（PRD 明确选择）：输入实际余额 → **直接覆盖账面余额** + 写一条留痕，
/// **不生成差额调整流水**。理由是差额通常来自漏记/多记，自动造一笔「调整」流水
/// 会污染收支报表的口径。
struct ReconcileScreen: View {
    @EnvironmentObject private var env: AppEnvironment

    let accountId: String
    let onChange: () -> Void

    @State private var loading = true
    @State private var saving = false
    @State private var message: String?

    @State private var account: Account?
    @State private var actualBalanceText = ""
    @State private var note = ""
    @State private var history: [Reconciliation] = []
    @State private var subscription: Subscription?

    private var actualBalanceCents: Int64? { Fmt.parseAmount(actualBalanceText) }

    /// 与实际余额的差额（分）：正数 = 账面少了钱，负数 = 账面多了钱
    private var differenceCents: Int64? {
        guard let actual = actualBalanceCents, let book = account?.currentBalance else { return nil }
        return actual - book
    }

    private var canSave: Bool { actualBalanceCents != nil && !saving }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    if let message = message {
                        Section { MessageBanner(text: message) }
                    }

                    Section {
                        HStack {
                            Text("账面余额")
                                .foregroundColor(BrandColor.onSurfaceVariant)
                            Spacer()
                            Text(Fmt.money(account?.currentBalance ?? 0))
                                .font(.title3.weight(.semibold))
                                .foregroundColor((account?.currentBalance ?? 0) < 0
                                                 ? SemanticColor.expense
                                                 : BrandColor.onSurface)
                        }
                    } footer: {
                        Text("账面余额 = 初始余额 + 所有已记流水的联动结果。")
                    }

                    Section("实际余额") {
                        HStack {
                            Text("¥").foregroundColor(BrandColor.onSurfaceVariant)
                            TextField("0", text: actualBalanceBinding)
                                .keyboardType(.numbersAndPunctuation)
                                .multilineTextAlignment(.trailing)
                        }
                        if let difference = differenceCents, difference != 0 {
                            HStack(spacing: 6) {
                                Image(systemName: "exclamationmark.circle")
                                Text(difference > 0
                                     ? "比账面多 \(Fmt.money(difference))"
                                     : "比账面少 \(Fmt.money(-difference))")
                            }
                            .font(.caption)
                            .foregroundColor(BrandColor.warningTint)
                        }
                        TextField("备注（选填，比如「现金盘点」）", text: $note)
                    }

                    Section {
                        Button {
                            Task { await reconcile() }
                        } label: {
                            Text(saving ? "处理中…" : "按实际余额校准")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(!canSave)
                    } footer: {
                        Text("校准会直接改写账面余额并留下一条对账记录，不会生成额外的流水。")
                    }

                    Section("对账历史") {
                        if history.isEmpty {
                            Text("还没有对账记录").font(.caption)
                        } else {
                            ForEach(history, id: \.reconciliationId) { record in
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(Fmt.dateTime(record.reconciledAt))
                                            .font(.subheadline)
                                            .foregroundColor(BrandColor.onSurface)
                                        if let note = record.note, !note.isEmpty {
                                            Text(note)
                                                .font(.caption)
                                                .foregroundColor(BrandColor.onSurfaceVariant)
                                        }
                                    }
                                    Spacer()
                                    Text(Fmt.money(record.balance))
                                        .font(.subheadline.weight(.medium))
                                        .foregroundColor(BrandColor.onSurface)
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("对账")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: subscribe)
        .onDisappear { subscription?.cancel(); subscription = nil }
        .task { await load() }
    }

    private var actualBalanceBinding: Binding<String> {
        Binding(
            get: { actualBalanceText },
            set: { if isNumericInput($0) { actualBalanceText = $0 } }
        )
    }

    private func subscribe() {
        guard subscription == nil else { return }
        subscription = env.facade.observeReconciliations(accountId: accountId) { value in
            history = value
        }
    }

    private func load() async {
        defer { loading = false }
        do {
            let loaded = try await env.facade.accountById(accountId: accountId)
            account = loaded
            actualBalanceText = loaded.map { Fmt.plainYuan($0.currentBalance) } ?? ""
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func reconcile() async {
        guard let cents = actualBalanceCents, canSave else { return }
        saving = true
        defer { saving = false }
        do {
            _ = try await env.facade.reconcileAccount(
                // reconciliationId 由调用方生成（与 transactionId 同一策略，PRD §7.7）
                reconciliationId: Fmt.newReconciliationId(),
                accountId: accountId,
                actualBalanceCents: cents,
                note: note
            )
            await load()
            message = "已按实际余额校准"
            onChange()
        } catch {
            message = ledgerMessage(error)
        }
    }
}
