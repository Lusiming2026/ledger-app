import SwiftUI
import Shared

/// P02 首页总览（PRD §4.2 / §11.1）。
///
/// 数据两条通道，与 Android 侧同构：
///  - 账户与最近流水走**订阅**（保存一笔后立刻刷新，PRD §11.1 < 200ms）；
///  - 本月汇总与预算状态走**一次性查询**（聚合 SQL），进入页面时刷。
///
/// 用 `List` 而不是 `ScrollView`：只有 List 的行才支持 `.swipeActions`，
/// 而「流水左滑删除」是 PRD §4.2 明确要求的关键交互。
struct HomeView: View {
    @EnvironmentObject private var env: AppEnvironment

    let onRecord: () -> Void
    let onOpenList: () -> Void
    let onOpenBudget: () -> Void
    /// 数据发生变化（删除流水）后通知上层让报表重算
    let onChange: () -> Void
    let refreshToken: Int

    @State private var accounts: [Account] = []
    @State private var recent: [Transaction] = []
    @State private var overview: HomeOverview?
    @State private var warningLevel: Int32 = 0
    @State private var message: String?
    @State private var confirm: ConfirmSpec?
    @State private var editTarget: RecordTarget?
    @State private var subscriptions: [Subscription] = []

    var body: some View {
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
                monthCard
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 12, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            Section {
                budgetCard
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 12, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            Section {
                accountsCard
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 12, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }

            Section {
                if recent.isEmpty {
                    LedgerCard {
                        EmptyHint(
                            text: "还没有流水。点下面「记一笔」，只输金额就能存下第一笔。",
                            actionLabel: "记一笔",
                            onAction: onRecord
                        )
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 12, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                } else {
                    ForEach(recent, id: \.transactionId) { transaction in
                        Button {
                            editTarget = RecordTarget(transactionId: transaction.transactionId)
                        } label: {
                            TransactionRowView(
                                transaction: transaction,
                                subtitle: accountName(transaction)
                            )
                        }
                        .buttonStyle(.plain)
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        .listRowBackground(BrandColor.surfaceContainer)
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                confirm = ConfirmSpec(
                                    title: "删除这笔流水？",
                                    message: "删除后余额会一并回滚。这是软删除，历史备份里不会包含它。",
                                    onConfirm: { delete(transaction) }
                                )
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                    Color.clear
                        .frame(height: 72)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
            } header: {
                HStack {
                    Text("最近流水")
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(BrandColor.onSurfaceVariant)
                    Spacer()
                    Button("查看全部", action: onOpenList).font(.subheadline)
                }
            }
        }
        .listStyle(.plain)
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("记账")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: onRecord) {
                    Label("记一笔", systemImage: "plus")
                }
            }
        }
        .overlay(alignment: .bottom) {
            Button(action: onRecord) {
                HStack(spacing: 8) {
                    Image(systemName: "plus.circle.fill")
                    Text("记一笔").fontWeight(.semibold)
                }
                .padding(.horizontal, 22)
                .padding(.vertical, 14)
                .background(BrandColor.primary)
                .foregroundColor(BrandColor.onPrimary)
                .clipShape(Capsule())
                .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
            }
            .padding(.bottom, 20)
        }
        .alert(item: $confirm) { spec in
            Alert(
                title: Text(spec.title),
                message: Text(spec.message),
                primaryButton: .destructive(Text(spec.confirmLabel), action: spec.onConfirm),
                secondaryButton: .cancel(Text("取消"))
            )
        }
        .sheet(item: $editTarget) { target in
            RecordView(transactionId: target.transactionId) {
                editTarget = nil
                Task { await reload() }
                onChange()
            }
            .environmentObject(env)
        }
        .onAppear(perform: subscribe)
        .onDisappear(perform: unsubscribe)
        .task(id: refreshToken) { await reload() }
        .refreshable { await reload() }
    }

    // ------------------------------------------------------------ 数据

    private func subscribe() {
        guard subscriptions.isEmpty else { return }
        let facade = env.facade
        subscriptions.append(facade.observeAccounts { value in accounts = value })
        subscriptions.append(facade.observeRecentTransactions { value in recent = value })
    }

    private func unsubscribe() {
        subscriptions.forEach { $0.cancel() }
        subscriptions.removeAll()
    }

    private func reload() async {
        do {
            let loaded = try await env.facade.homeOverview.load(recentLimit: 10)
            overview = loaded
            warningLevel = loaded.budgetStatus.map {
                env.facade.budgetService.inAppWarningLevel(status: $0)
            } ?? 0
            // 进入首页顺手做一次预算阈值检查：跨月后新周期预算立即生效（PRD §11.4）
            env.checkBudgetNotification()
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func delete(_ transaction: Transaction) {
        Task {
            do {
                let result = try await env.facade.deleteTransaction(
                    transactionId: transaction.transactionId,
                    expectedUpdatedAtMillis: transaction.updatedAt
                )
                message = result.deleted ? "已删除，余额已回滚" : "该流水已被删除"
                // 首页只订阅「最近流水」，删除后列表会自己刷新；但本月汇总与预算进度
                // 是聚合查询出来的，要让上层知道需要重算。
                onChange()
            } catch {
                message = ledgerMessage(error)
            }
        }
    }

    private func accountName(_ transaction: Transaction) -> String? {
        let id = transaction.fromAccountId ?? transaction.toAccountId
        guard let id = id else { return nil }
        return accounts.first { $0.accountId == id }?.name
    }

    // ------------------------------------------------------------ 卡片

    private var monthCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(overview?.yearMonth.displayLabel ?? "本月") 结余")
                .font(.subheadline)
                .foregroundColor(BrandColor.onPrimaryContainer.opacity(0.75))
            AmountText(cents: overview?.summary.balanceCents ?? 0, signed: true)
                .foregroundColor(BrandColor.onPrimaryContainer)

            HStack(spacing: 24) {
                statColumn("收入", overview?.summary.incomeCents ?? 0, SemanticColor.income)
                statColumn("支出", overview?.summary.expenseCents ?? 0, SemanticColor.expense)
                Spacer()
                Button("看明细", action: onOpenList)
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onPrimaryContainer)
            }
            .padding(.top, 12)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(BrandColor.primaryContainer)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func statColumn(_ label: String, _ cents: Int64, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundColor(BrandColor.onPrimaryContainer.opacity(0.75))
            Text(Fmt.money(cents))
                .font(.subheadline.weight(.medium))
                .foregroundColor(color)
        }
    }

    private var budgetCard: some View {
        LedgerCard(
            "本月预算",
            trailing: AnyView(
                Button(overview?.budgetStatus?.hasBudget == true ? "调整" : "设置") {
                    onOpenBudget()
                }
                .font(.subheadline)
            )
        ) {
            if let status = overview?.budgetStatus, status.hasBudget {
                let accent = SemanticColor.forWarningLevel(warningLevel)
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(Fmt.money(status.spentCents)) / \(Fmt.money(status.budgetCents))")
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(BrandColor.onSurface)
                        Text(status.remainingCents >= 0
                             ? "剩余 \(Fmt.money(status.remainingCents))"
                             : "已超支 \(Fmt.money(-status.remainingCents))")
                            .font(.caption)
                            .foregroundColor(status.remainingCents >= 0
                                             ? BrandColor.onSurfaceVariant
                                             : SemanticColor.expense)
                    }
                    Spacer()
                    Text(Fmt.percent(status.usageRatio))
                        .font(.title3.weight(.semibold))
                        .foregroundColor(accent)
                }

                ProgressView(value: Double(status.progressFraction))
                    .tint(accent)

                if warningLevel > 0 {
                    // 通知权限被拒时的 App 内兜底预警（PRD §11.4）
                    HStack(spacing: 6) {
                        Image(systemName: "exclamationmark.triangle.fill")
                        Text(warningLevel >= 2 ? "已用完本月预算" : "已用掉预算的 80%")
                    }
                    .font(.caption)
                    .foregroundColor(accent)
                }
            } else {
                Text("还没设预算。设一个月度总额，花费到 80% 和 100% 时会提醒你。")
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            }
        }
    }

    private var accountsCard: some View {
        LedgerCard(
            "账户",
            trailing: AnyView(
                Text("总余额 \(Fmt.money(overview?.totalBalanceCents ?? 0))")
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurface)
            )
        ) {
            if accounts.isEmpty {
                Text("还没有账户。")
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(accounts, id: \.accountId) { account in
                            HStack(spacing: 8) {
                                IconBadge(
                                    systemName: LedgerIcon.accountSymbol(typeCode: account.type.dbValue),
                                    tint: account.isArchived ? BrandColor.outline : BrandColor.primary,
                                    size: 30
                                )
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(account.name + (account.isArchived ? "（已归档）" : ""))
                                        .font(.caption)
                                        .foregroundColor(BrandColor.onSurfaceVariant)
                                    Text(Fmt.money(account.currentBalance))
                                        .font(.subheadline.weight(.medium))
                                        .foregroundColor(account.currentBalance < 0
                                                         ? SemanticColor.expense
                                                         : BrandColor.onSurface)
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(BrandColor.surfaceContainerHigh)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                }
            }
        }
    }
}

/// 流水行（首页简化版：备注/类型 + 账户 + 金额）。
struct TransactionRowView: View {
    let transaction: Transaction
    var subtitle: String?

    private var typeCode: String { transaction.type.dbValue }

    private var title: String {
        if let note = transaction.note, !note.isEmpty { return note }
        switch typeCode {
        case "expense": return "支出"
        case "income": return "收入"
        default: return "转账"
        }
    }

    private var amountText: String {
        switch typeCode {
        case "expense": return "-" + Fmt.money(transaction.amount)
        case "income": return "+" + Fmt.money(transaction.amount)
        default: return Fmt.money(transaction.amount)
        }
    }

    private var iconName: String {
        switch typeCode {
        case "expense": return "arrow.down"
        case "income": return "arrow.up"
        default: return "arrow.left.arrow.right"
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            IconBadge(
                systemName: iconName,
                tint: SemanticColor.forTransactionType(typeCode),
                size: 32
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurface)
                    .lineLimit(1)
                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(amountText)
                .font(.subheadline.weight(.medium))
                .foregroundColor(SemanticColor.forTransactionType(typeCode))
        }
        .padding(.vertical, 10)
    }
}
