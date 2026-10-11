import SwiftUI
import Shared

/// P03 快速记账 / P04 编辑流水（PRD §4.2 / §11.1）。
///
/// 目标是「≤ 3 步记一笔」：切类型 →（选分类）→ 敲金额 → 保存。
/// 因此布局自上而下就是决策顺序，账户/备注/日期都是**预填好的、可以不管的**：
///  - 类型、分类、账户都按 `PreferenceKeys` 记忆上次值（PRD §11.1）；
///  - 日期默认「现在」，用户要改才点开选择器。
///
/// 金额键盘是**自制**的，不是系统数字键盘。原因：金额要高精度整数运算
/// （全程「分」，禁用浮点，PRD §11.6），自制键盘能直接约束到「最多 2 位小数、
/// 整数位最多 7 位」，把非法输入挡在产生之前，而不是事后报错。
///
/// P04 编辑模式的两条硬约束：
///  - **type 不可修改**（改类型会让历史流水的余额联动语义失效），所以隐藏类型切换；
///  - 带 `updatedAt` 作乐观锁版本，若在别处改过会提示「已在别处被修改」。
struct RecordView: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    /// nil = 新增（P03）；非 nil = 编辑（P04）
    let transactionId: String?
    let onDone: () -> Void

    // ---- 加载状态
    @State private var loading = true
    @State private var saving = false
    @State private var message: String?

    /// 编辑模式下的原流水（同时提供乐观锁版本 `updatedAt`）
    @State private var editing: Transaction?

    // ---- 表单
    @State private var typeCode = "expense"
    @State private var categories: [Category] = []
    @State private var selectedCategoryId: String?
    @State private var accounts: [Account] = []
    @State private var fromAccountId: String?
    @State private var toAccountId: String?
    @State private var note = ""
    @State private var occurredAtMillis: Int64 = 0
    @State private var amountText = ""

    @State private var categorySubscription: Subscription?
    @State private var accountSubscription: Subscription?
    @State private var alert: ConfirmSpec?

    private var isEdit: Bool { transactionId != nil }

    /// 该记账类型需要的分类类型；transfer 为 nil（转账不挂分类）
    private var kindCode: String? {
        switch typeCode {
        case "expense": return "expense"
        case "income": return "income"
        default: return nil
        }
    }

    /// 支出、转账需要「转出账户」
    private var needsFromAccount: Bool { typeCode != "income" }

    /// 收入、转账需要「转入账户」
    private var needsToAccount: Bool { typeCode != "expense" }

    private var amountCents: Int64? {
        guard let cents = Fmt.parseAmount(amountText), cents > 0 else { return nil }
        return cents
    }

    private var canSave: Bool {
        guard !saving, amountCents != nil else { return false }
        if kindCode != nil && selectedCategoryId == nil { return false }
        if needsFromAccount && fromAccountId == nil { return false }
        if needsToAccount && toAccountId == nil { return false }
        // 转账两个账户不能相同（R-TRF-01，领域层也会拦，这里提前给出可读反馈）
        if typeCode == "transfer" && fromAccountId == toAccountId { return false }
        return true
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                if loading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            if !isEdit { typeTabs } else { editingTypeRow }
                            if kindCode != nil { categoryPicker }
                            accountPicker
                            noteAndDate
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                        .padding(.bottom, 8)
                    }
                    amountBar
                    keypad
                }
            }
            .background(BrandColor.background.ignoresSafeArea())
            .navigationTitle(isEdit ? "编辑流水" : "记一笔")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if isEdit {
                        Button(role: .destructive) {
                            alert = ConfirmSpec(
                                title: "删除这笔流水？",
                                message: "删除后余额会一并回滚。这是软删除，历史备份里不会包含它。",
                                onConfirm: { Task { await deleteExisting() } }
                            )
                        } label: {
                            Image(systemName: "trash")
                        }
                        .disabled(saving)
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
        .alert(item: $alert) { spec in
            Alert(
                title: Text(spec.title),
                message: Text(spec.message),
                primaryButton: .destructive(Text(spec.confirmLabel), action: spec.onConfirm),
                secondaryButton: .cancel(Text("取消"))
            )
        }
        .task {
            await load()
            subscribeAccounts()
            rebuildCategorySubscription()
        }
        .onDisappear {
            categorySubscription?.cancel()
            accountSubscription?.cancel()
        }
    }

    // ============================================================ 类型

    private var typeTabs: some View {
        HStack(spacing: 8) {
            ForEach(["expense", "income", "transfer"], id: \.self) { code in
                let selected = code == typeCode
                Button {
                    switchType(to: code)
                } label: {
                    Text(IosUiBridgeKt.transactionTypeLabel(code: code))
                        .font(.subheadline.weight(selected ? .semibold : .regular))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(selected
                                    ? BrandColor.primary
                                    : BrandColor.surfaceContainerHigh)
                        .foregroundColor(selected
                                         ? BrandColor.onPrimary
                                         : BrandColor.onSurfaceVariant)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// 编辑模式：类型只读展示（P04 不支持改类型）
    private var editingTypeRow: some View {
        HStack(spacing: 8) {
            Text(IosUiBridgeKt.transactionTypeLabel(code: typeCode))
                .font(.subheadline.weight(.semibold))
                .foregroundColor(BrandColor.onSurface)
            Text("类型不可修改")
                .font(.caption)
                .foregroundColor(BrandColor.onSurfaceVariant)
            Spacer()
        }
    }

    // ============================================================ 分类

    private var categoryPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("分类")
                .font(.subheadline.weight(.medium))
                .foregroundColor(BrandColor.onSurfaceVariant)

            if categories.isEmpty {
                Text("还没有可用分类，去「设置 → 分类管理」新建一个。")
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            } else {
                // 手写 5 列网格（与 Android 侧同构）：分类数通常 8–16 个，
                // 一屏铺开比横向滚动更快选到。
                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5),
                    spacing: 10
                ) {
                    ForEach(categories, id: \.categoryId) { category in
                        categoryCell(category)
                    }
                }
            }
        }
    }

    private func categoryCell(_ category: Category) -> some View {
        let selected = category.categoryId == selectedCategoryId
        let tint = Color(hex: category.color)
        return Button {
            selectedCategoryId = category.categoryId
        } label: {
            VStack(spacing: 4) {
                IconBadge(
                    systemName: LedgerIcon.symbol(for: category.icon),
                    tint: selected ? tint : BrandColor.onSurfaceVariant,
                    size: 34
                )
                Text(category.name)
                    .font(.caption2)
                    .lineLimit(1)
                    .foregroundColor(selected ? BrandColor.onSurface : BrandColor.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
            .background(selected ? tint.opacity(0.14) : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // ============================================================ 账户

    private var accountPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            if accounts.isEmpty {
                LedgerCard("账户") {
                    Text("还没有可用账户，去「设置 → 账户管理」新建一个。")
                        .font(.caption)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                }
            } else if typeCode == "transfer" {
                accountRow(title: "转出账户", selection: $fromAccountId)
                HStack {
                    Spacer()
                    Button {
                        // 交换转出/转入
                        let tmp = fromAccountId
                        fromAccountId = toAccountId
                        toAccountId = tmp
                    } label: {
                        Label("交换", systemImage: "arrow.up.arrow.down")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                    Spacer()
                }
                accountRow(title: "转入账户", selection: $toAccountId)
            } else {
                accountRow(
                    title: needsFromAccount ? "转出账户" : "转入账户",
                    selection: needsFromAccount ? $fromAccountId : $toAccountId
                )
            }
        }
    }

    private func accountRow(title: String, selection: Binding<String?>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundColor(BrandColor.onSurfaceVariant)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(accounts, id: \.accountId) { account in
                        let selected = account.accountId == selection.wrappedValue
                        Button {
                            selection.wrappedValue = account.accountId
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: LedgerIcon.accountSymbol(
                                    typeCode: account.type.dbValue
                                ))
                                .font(.caption)
                                Text(account.name).font(.subheadline)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(selected
                                        ? BrandColor.primary
                                        : BrandColor.surfaceContainerHigh)
                            .foregroundColor(selected
                                             ? BrandColor.onPrimary
                                             : BrandColor.onSurface)
                            .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    // ============================================================ 备注与日期

    private var noteAndDate: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("备注")
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(BrandColor.onSurfaceVariant)
                Spacer()
                Text("\(note.count)/50")
                    .font(.caption2)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            }
            TextField("选填，比如「午饭」", text: noteBinding)
                .textFieldStyle(.roundedBorder)

            HStack {
                Text("日期")
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(BrandColor.onSurfaceVariant)
                Spacer()
                DatePicker(
                    "",
                    selection: pickedDateBinding,
                    displayedComponents: [.date]
                )
                .labelsHidden()
                Text(Fmt.shortDate(occurredAtMillis))
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurface)
            }
        }
    }

    /// 备注上限 50 字（R-NOT-01）。在输入处截断，比弹错更友好。
    private var noteBinding: Binding<String> {
        Binding(get: { note }, set: { note = String($0.prefix(50)) })
    }

    /// 日期选择器的绑定。
    ///
    /// **与 Android 侧的差异（值得记一笔）**：Android 的 `DatePickerDialog` 回调给的是
    /// 「所选那天的 UTC 零点」，直接当本地零点用会在东八区偏一天，所以要手工纠偏；
    /// 而 SwiftUI 的 `Date` 本身就是绝对时间点，`DatePicker` 显示与回写都按设备时区，
    /// **不存在偏一天的问题**。
    ///
    /// 这里唯一要做的是：用户只改年月日时，把原来的时分秒拼回去，
    /// 否则「编辑一笔 14:30 的记录、只改了日期」会顺手把时间清成 00:00。
    private var pickedDateBinding: Binding<Date> {
        Binding(
            get: {
                let calendar = Calendar.current
                let current = Date(timeIntervalSince1970: Double(occurredAtMillis) / 1000)
                let day = calendar.dateComponents([.year, .month, .day], from: current)
                return calendar.date(from: day) ?? current
            },
            set: { newValue in
                let calendar = Calendar.current
                let current = Date(timeIntervalSince1970: Double(occurredAtMillis) / 1000)
                var merged = calendar.dateComponents([.year, .month, .day], from: newValue)
                let time = calendar.dateComponents([.hour, .minute, .second], from: current)
                merged.hour = time.hour
                merged.minute = time.minute
                merged.second = time.second
                if let date = calendar.date(from: merged) {
                    occurredAtMillis = Int64((date.timeIntervalSince1970 * 1000).rounded())
                }
            }
        )
    }

    // ============================================================ 金额与键盘

    private var amountBar: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("¥")
                .font(.title3.weight(.medium))
                .foregroundColor(BrandColor.onSurfaceVariant)
            Text(amountText.isEmpty ? "0.00" : amountText)
                .font(.system(size: 34, weight: .semibold))
                .foregroundColor(amountText.isEmpty
                                 ? BrandColor.onSurfaceVariant.opacity(0.5)
                                 : BrandColor.onSurface)
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                backspace()
            } label: {
                Image(systemName: "delete.left")
                    .font(.title3)
                    .foregroundColor(BrandColor.onSurfaceVariant)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .disabled(amountText.isEmpty)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(BrandColor.surfaceContainer)
        .overlay(alignment: .top) {
            Rectangle().fill(BrandColor.outlineVariant).frame(height: 0.5)
        }
    }

    private var keypad: some View {
        VStack(spacing: 6) {
            if let message = message {
                Text(message)
                    .font(.caption)
                    .foregroundColor(SemanticColor.expense)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }

            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3),
                spacing: 6
            ) {
                ForEach(["1", "2", "3", "4", "5", "6", "7", "8", "9"], id: \.self) { digit in
                    keypadKey(digit) { appendDigit(digit) }
                }
                keypadKey("00") { appendDoubleZero() }
                keypadKey("0") { appendDigit("0") }
                keypadKey(".") { appendDot() }
            }
            .padding(.horizontal, 10)

            Button {
                Task { await save() }
            } label: {
                Text(saving ? "保存中…" : "保存")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(canSave ? BrandColor.primary : BrandColor.outlineVariant)
                    .foregroundColor(canSave ? BrandColor.onPrimary : BrandColor.onSurfaceVariant)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(!canSave)
            .padding(.horizontal, 16)
            .padding(.bottom, 10)
        }
        .padding(.top, 8)
        .background(BrandColor.surfaceContainer)
    }

    private func keypadKey(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.title3.weight(.medium))
                .foregroundColor(BrandColor.onSurface)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(BrandColor.surfaceContainerHigh)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // ------------------------------------------------------------ 键盘输入规则

    /// 整数位最多 7 位、小数最多 2 位（金额上限 9,999,999.99 元）
    private func appendDigit(_ digit: String) {
        guard let dotIndex = amountText.firstIndex(of: ".") else {
            if amountText.count >= 7 { return }
            // 避免 "00" 这种前导零堆叠
            if amountText == "0" { amountText = digit; return }
            amountText += digit
            return
        }
        let fractionCount = amountText.distance(from: dotIndex, to: amountText.endIndex) - 1
        if fractionCount >= 2 { return }
        amountText += digit
    }

    private func appendDot() {
        guard !amountText.isEmpty, !amountText.contains(".") else { return }
        amountText += "."
    }

    private func appendDoubleZero() {
        guard !amountText.isEmpty else { return }
        // 位数上限交给 appendDigit 判断，这里只管「按一下加两个 0」
        appendDigit("0")
        appendDigit("0")
    }

    private func backspace() {
        guard !amountText.isEmpty else { return }
        amountText.removeLast()
    }

    // ============================================================ 数据

    private func load() async {
        let facade = env.facade

        if let id = transactionId {
            // ---- P04 编辑：原样回填，不做任何「记忆值」覆盖
            do {
                guard let existing = try await facade.transactionById(transactionId: id) else {
                    message = "流水不存在或已被删除"
                    loading = false
                    return
                }
                editing = existing
                typeCode = existing.type.dbValue
                amountText = Fmt.plainYuan(existing.amount)
                note = existing.note ?? ""
                occurredAtMillis = existing.occurredAt
                selectedCategoryId = existing.categoryId
                fromAccountId = existing.fromAccountId
                toAccountId = existing.toAccountId
            } catch {
                message = ledgerMessage(error)
            }
            loading = false
            return
        }

        // ---- P03 新增：按记忆值预填（PRD §11.1「记忆上次值」）
        occurredAtMillis = facade.nowUtcMillis()

        if let remembered = facade.preference.getString(key: IosUiBridgeKt.prefKeyLastTransactionType()),
           ["expense", "income", "transfer"].contains(remembered) {
            typeCode = remembered
        }

        let active = (try? await facade.activeAccounts()) ?? []
        accounts = active
        fromAccountId = rememberedAccount(
            in: active,
            key: IosUiBridgeKt.prefKeyLastFromAccount()
        ) ?? active.first?.accountId
        toAccountId = rememberedAccount(in: active, key: IosUiBridgeKt.prefKeyLastToAccount())

        loading = false
    }

    private func rememberedAccount(in accounts: [Account], key: String) -> String? {
        guard let id = env.facade.preference.getString(key: key) else { return nil }
        return accounts.first { $0.accountId == id }?.accountId
    }

    private func subscribeAccounts() {
        guard accountSubscription == nil else { return }
        accountSubscription = env.facade.observeAccountsActive { value in
            accounts = value
            // 账户被归档后从列表消失：清掉指向它的选择，避免保存时报「账户已归档」
            if let from = fromAccountId, !value.contains(where: { $0.accountId == from }) {
                fromAccountId = value.first?.accountId
            }
            if let to = toAccountId, !value.contains(where: { $0.accountId == to }) {
                toAccountId = nil
            }
        }
    }

    /// 分类订阅随类型切换重建：`observeActive(kind)` 已经把「未归档 + 对应 kind」过滤掉了，
    /// 这样 R-CAT-04（分类类型与记账类型不匹配）在 UI 层就不可能被违反。
    private func rebuildCategorySubscription() {
        categorySubscription?.cancel()
        categorySubscription = nil
        categories = []

        guard let kind = kindCode else {
            // 转账不挂分类
            selectedCategoryId = nil
            return
        }

        let remembered = env.facade.preference.getString(
            key: IosUiBridgeKt.prefKeyLastCategory(kindCode: kind)
        )
        categorySubscription = env.facade.observeCategoriesByKindCode(kindCode: kind) { value in
            categories = value
            guard !isEdit else { return }
            if let current = selectedCategoryId,
               value.contains(where: { $0.categoryId == current }) {
                return
            }
            // 记忆值仍可用就沿用，否则清空等用户选
            selectedCategoryId = value.first { $0.categoryId == remembered }?.categoryId
        }
    }

    private func switchType(to code: String) {
        guard code != typeCode else { return }
        typeCode = code
        // R-CAT-04：切类型后分类必然不匹配，一律重算
        selectedCategoryId = nil
        rebuildCategorySubscription()
    }

    // ============================================================ 保存 / 删除

    private func save() async {
        guard let cents = amountCents, canSave else { return }
        saving = true
        message = nil
        defer { saving = false }

        // 组合约束：expense 只填转出、income 只填转入、transfer 两个都填
        // （领域层的 normalizeCombination 也会兜底，这里传干净值便于阅读）
        let fromId = needsFromAccount ? fromAccountId : nil
        let toId = needsToAccount ? toAccountId : nil
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            if let existing = editing {
                _ = try await env.facade.updateTransaction(
                    transactionId: existing.transactionId,
                    expectedUpdatedAtMillis: existing.updatedAt,
                    amountCents: cents,
                    categoryId: kindCode == nil ? nil : selectedCategoryId,
                    fromAccountId: fromId,
                    toAccountId: toId,
                    note: trimmedNote.isEmpty ? nil : trimmedNote,
                    occurredAtMillis: occurredAtMillis
                )
            } else if typeCode == "transfer" {
                _ = try await env.facade.transfer(
                    transactionId: Fmt.newTransactionId(),
                    amountCents: cents,
                    fromAccountId: fromId ?? "",
                    toAccountId: toId ?? "",
                    note: trimmedNote.isEmpty ? nil : trimmedNote,
                    occurredAtMillis: occurredAtMillis
                )
            } else {
                _ = try await env.facade.addTransaction(
                    transactionId: Fmt.newTransactionId(),
                    typeCode: typeCode,
                    amountCents: cents,
                    categoryId: selectedCategoryId,
                    fromAccountId: fromId,
                    toAccountId: toId,
                    note: trimmedNote.isEmpty ? nil : trimmedNote,
                    occurredAtMillis: occurredAtMillis
                )
            }

            rememberChoices()
            // 记账后立刻做一次预算阈值检查（PRD §7.3：达到 80% / 100% 提醒）
            env.checkBudgetNotification()
            onDone()
        } catch {
            message = ledgerMessage(error)
        }
    }

    /// 把本次选择写回偏好，下次进来自动带上（PRD §11.1）。
    private func rememberChoices() {
        let preference = env.facade.preference
        preference.putString(
            key: IosUiBridgeKt.prefKeyLastTransactionType(),
            value: typeCode
        )
        switch typeCode {
        case "expense":
            preference.putString(key: IosUiBridgeKt.prefKeyLastFromAccount(), value: fromAccountId)
            if let categoryId = selectedCategoryId {
                preference.putString(
                    key: IosUiBridgeKt.prefKeyLastCategory(kindCode: "expense"),
                    value: categoryId
                )
            }
        case "income":
            preference.putString(key: IosUiBridgeKt.prefKeyLastToAccount(), value: toAccountId)
            if let categoryId = selectedCategoryId {
                preference.putString(
                    key: IosUiBridgeKt.prefKeyLastCategory(kindCode: "income"),
                    value: categoryId
                )
            }
        default:
            preference.putString(key: IosUiBridgeKt.prefKeyLastFromAccount(), value: fromAccountId)
            preference.putString(key: IosUiBridgeKt.prefKeyLastToAccount(), value: toAccountId)
        }
    }

    private func deleteExisting() async {
        guard let existing = editing else { return }
        saving = true
        defer { saving = false }
        do {
            _ = try await env.facade.deleteTransaction(
                transactionId: existing.transactionId,
                expectedUpdatedAtMillis: existing.updatedAt
            )
            onDone()
        } catch {
            message = ledgerMessage(error)
        }
    }
}
