import SwiftUI
import Shared

/// P10 预算设置与进度（PRD §4.2 / §7.3 / §11.4）。
///
/// 口径：**月度总预算**是 MVP 唯一的预算粒度（每自然月一条，`uq_budget_period`），
/// 使用率 = 当月总支出 ÷ 预算额，只看 `expense` 且未软删除的流水。
///
/// 提醒的幂等判定不在这一层：真正「这个月 80% 提醒过了没有」的权威数据源是
/// `budget_notification` 表（唯一索引 `uq_notify_once`），所以这里不需要维护任何
/// 已提醒状态——反复保存/清空重设预算都不会重复打扰用户。
struct BudgetView: View {
    @EnvironmentObject private var env: AppEnvironment
    let onChange: () -> Void

    @State private var loading = true
    @State private var saving = false
    @State private var message: String?

    @State private var status: BudgetStatus?
    @State private var amountText = ""
    @State private var yearMonthLabel = ""
    @State private var warningLevel: Int32 = 0
    @State private var notificationsAllowed = false

    @State private var alert: ConfirmSpec?
    @State private var subscription: Subscription?

    private var amountCents: Int64? { Fmt.parseAmount(amountText) }

    private var canSave: Bool {
        guard let cents = amountCents, cents > 0 else { return false }
        return !saving
    }

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
                        progressCard
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }

                    Section("\(yearMonthLabel)预算") {
                        HStack {
                            Text("¥").foregroundColor(BrandColor.onSurfaceVariant)
                            TextField("比如 5000", text: amountBinding)
                                .keyboardType(.numbersAndPunctuation)
                                .multilineTextAlignment(.trailing)
                        }
                        Button {
                            Task { await save() }
                        } label: {
                            Text(saving ? "保存中…" : "保存预算")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(!canSave)

                        if status?.hasBudget == true {
                            Button(role: .destructive) {
                                alert = ConfirmSpec(
                                    title: "删除\(yearMonthLabel)预算？",
                                    message: "删除后该月不再有预算进度与提醒，已投递过的通知记录也会一并清理。",
                                    confirmLabel: "删除",
                                    onConfirm: { Task { await deleteBudget() } }
                                )
                            } label: {
                                Text("删除预算").frame(maxWidth: .infinity)
                            }
                            .disabled(saving)
                        }
                    }

                    Section {
                        HStack {
                            Image(systemName: notificationsAllowed
                                  ? "bell.badge.fill"
                                  : "bell.slash.fill")
                            .foregroundColor(notificationsAllowed
                                             ? SemanticColor.budgetNormal
                                             : BrandColor.warningTint)
                            Text(notificationsAllowed ? "通知已开启" : "通知未开启")
                                .font(.subheadline)
                            Spacer()
                            if !notificationsAllowed {
                                Button("去开启") { requestPermission() }
                                    .font(.subheadline)
                            }
                        }
                    } footer: {
                        Text("未开启通知时不会漏掉预警：达到 80% / 100% 会在首页与这里的进度条上用颜色提示。")
                    }

                    Section {
                        Text("口径：使用率 = 当月总支出 ÷ 预算额。转账不计入支出，删除的流水也不计入。每个月最多在 80% 与 100% 各提醒一次。")
                            .font(.caption)
                            .foregroundColor(BrandColor.onSurfaceVariant)
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("预算")
        .navigationBarTitleDisplayMode(.inline)
        .alert(item: $alert) { spec in
            Alert(
                title: Text(spec.title),
                message: Text(spec.message),
                primaryButton: .destructive(Text(spec.confirmLabel), action: spec.onConfirm),
                secondaryButton: .cancel(Text("取消"))
            )
        }
        .onAppear(perform: subscribe)
        .onDisappear { subscription?.cancel(); subscription = nil }
        .task { await load() }
    }

    // ------------------------------------------------------------ 进度卡

    private var progressCard: some View {
        let accent = SemanticColor.forWarningLevel(warningLevel)
        return VStack(alignment: .leading, spacing: 10) {
            if let status = status, status.hasBudget {
                Text("\(yearMonthLabel)预算 \(Fmt.money(status.budgetCents))")
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurfaceVariant)

                HStack(alignment: .firstTextBaseline) {
                    Text(Fmt.money(status.spentCents))
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundColor(BrandColor.onSurface)
                    Text("已花")
                        .font(.caption)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                    Spacer()
                    Text(Fmt.percent(status.usageRatio))
                        .font(.title3.weight(.semibold))
                        .foregroundColor(accent)
                }

                ProgressView(value: Double(status.progressFraction))
                    .tint(accent)

                HStack {
                    Text(status.remainingCents >= 0
                         ? "剩余 \(Fmt.money(status.remainingCents))"
                         : "已超支 \(Fmt.money(-status.remainingCents))")
                        .font(.subheadline)
                        .foregroundColor(status.remainingCents >= 0
                                         ? BrandColor.onSurfaceVariant
                                         : SemanticColor.expense)
                    Spacer()
                }

                if warningLevel > 0 {
                    HStack(spacing: 6) {
                        Image(systemName: "exclamationmark.triangle.fill")
                        Text(warningLevel >= 2 ? "已用完本月预算" : "已用掉预算的 80%")
                    }
                    .font(.caption)
                    .foregroundColor(accent)
                }
            } else {
                Text("还没设\(yearMonthLabel)预算")
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurfaceVariant)
                Text("设一个月度总额，花到 80% 和 100% 时会提醒你。")
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
                Text("当月已支出 \(Fmt.money(status?.spentCents ?? 0))")
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BrandColor.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    // ------------------------------------------------------------ 数据

    private var amountBinding: Binding<String> {
        Binding(
            get: { amountText },
            set: { if isNumericInput($0) { amountText = $0 } }
        )
    }

    private func subscribe() {
        guard subscription == nil else { return }
        // 保存一笔支出后预算进度要立刻跟着动，所以订阅而不是只查一次
        subscription = env.facade.observeCurrentBudget { budget in
            if let budget = budget {
                amountText = Fmt.plainYuan(budget.amount)
            }
            Task { await refreshStatus() }
        }
    }

    private func load() async {
        defer { loading = false }
        yearMonthLabel = env.facade.currentYearMonthLabel()
        notificationsAllowed = env.facade.notifications.hasPermission()
        await refreshStatus()
        if status?.hasBudget != true {
            amountText = ""
        }
    }

    private func refreshStatus() async {
        do {
            let loaded = try await env.facade.currentBudgetStatus()
            status = loaded
            warningLevel = env.facade.budgetService.inAppWarningLevel(status: loaded)
            if loaded.hasBudget && amountText.isEmpty {
                amountText = Fmt.plainYuan(loaded.budgetCents)
            }
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func save() async {
        guard let cents = amountCents, cents > 0 else { return }
        saving = true
        defer { saving = false }
        do {
            // 同月已存在时仓储会复用原 budget_id，保住通知幂等链
            _ = try await env.facade.saveCurrentBudget(
                budgetId: Fmt.newBudgetId(),
                amountCents: cents
            )
            await refreshStatus()
            message = "已保存 \(yearMonthLabel)预算"
            // 预算刚被调低时可能立刻就越线，主动查一次阈值
            env.checkBudgetNotification()
            onChange()
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func deleteBudget() async {
        guard let budgetId = status?.budget?.budgetId else { return }
        saving = true
        defer { saving = false }
        do {
            try await env.facade.deleteBudget(budgetId: budgetId)
            amountText = ""
            await refreshStatus()
            message = "已删除\(yearMonthLabel)预算"
            onChange()
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func requestPermission() {
        env.facade.notifications.requestPermission { granted in
            notificationsAllowed = granted
            message = granted ? "通知已开启" : "未获得通知权限，将改用页内颜色提示"
        }
    }
}
