import SwiftUI
import Shared

/// P12 流水明细（PRD §4.2 / §11.5）。
///
/// 一个页面承载三种入口：首页「查看全部」、报表下钻（按分类 / 按日）、以及
/// 分类页的「看该分类的流水」——差异只体现在 `TransactionListRequest` 的过滤参数上。
///
/// 分组规则：按**本地时区的自然日**分组（`Fmt.date`），组内按 `occurredAt` 倒序，
/// 日头显示当日支出/收入小计。日界用本地时区而不是 UTC，否则东八区用户在
/// 00:00–08:00 记的账会落到前一天。
struct TransactionListView: View {
    @EnvironmentObject private var env: AppEnvironment

    let request: TransactionListRequest
    /// 数据发生变化（编辑/删除）后通知上层刷新首页与报表
    let onChange: () -> Void

    @State private var groups: [DayGroup] = []
    @State private var loading = true
    @State private var message: String?
    @State private var alert: ConfirmSpec?
    @State private var editTarget: RecordTarget?

    /// 「全部流水」入口：0 .. Int64.max 视为无范围限制
    private var isAllRange: Bool {
        request.fromMillis == 0 && request.toMillis == Int64.max
    }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if groups.isEmpty {
                EmptyHint(text: "这个范围内还没有流水。")
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

                    if !isAllRange {
                        Section {
                            Text("范围：\(Fmt.date(request.fromMillis)) 起")
                                .font(.caption)
                                .foregroundColor(BrandColor.onSurfaceVariant)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        }
                    }

                    ForEach(groups) { group in
                        Section {
                            ForEach(group.items, id: \.id) { detail in
                                Button {
                                    editTarget = RecordTarget(
                                        transactionId: detail.transaction.transactionId
                                    )
                                } label: {
                                    detailRow(detail)
                                }
                                .buttonStyle(.plain)
                                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                                .listRowBackground(BrandColor.surfaceContainer)
                                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                    Button(role: .destructive) {
                                        alert = ConfirmSpec(
                                            title: "删除这笔流水？",
                                            message: "删除后余额会一并回滚。这是软删除，历史备份里不会包含它。",
                                            onConfirm: { Task { await delete(detail) } }
                                        )
                                    } label: {
                                        Label("删除", systemImage: "trash")
                                    }
                                }
                            }
                        } header: {
                            dayHeader(group)
                        }
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle(request.title)
        .navigationBarTitleDisplayMode(.inline)
        .alert(item: $alert) { spec in
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
                Task { await load() }
                onChange()
            }
            .environmentObject(env)
        }
        .task { await load() }
        .refreshable { await load() }
    }

    // ------------------------------------------------------------ 行

    private func detailRow(_ detail: TransactionDetail) -> some View {
        let typeCode = detail.transaction.type.dbValue
        return HStack(spacing: 12) {
            IconBadge(
                systemName: iconName(detail),
                tint: iconTint(detail, typeCode: typeCode),
                size: 34
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(title(detail))
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurface)
                    .lineLimit(1)
                Text(subtitle(detail))
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 2) {
                Text(amountText(detail))
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(SemanticColor.forTransactionType(typeCode))
                Text(Fmt.dateTime(detail.transaction.occurredAt))
                    .font(.caption2)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    private func dayHeader(_ group: DayGroup) -> some View {
        HStack {
            Text(group.dayLabel)
                .font(.caption.weight(.medium))
                .foregroundColor(BrandColor.onSurfaceVariant)
            Spacer()
            if group.expenseCents > 0 {
                Text("支出 \(Fmt.money(group.expenseCents))")
                    .font(.caption2)
                    .foregroundColor(SemanticColor.expense)
            }
            if group.incomeCents > 0 {
                Text("收入 \(Fmt.money(group.incomeCents))")
                    .font(.caption2)
                    .foregroundColor(SemanticColor.income)
            }
        }
    }

    // ------------------------------------------------------------ 展示文本

    private func title(_ detail: TransactionDetail) -> String {
        if let note = detail.transaction.note, !note.isEmpty { return note }
        return detail.categoryLabelForExport
    }

    private func subtitle(_ detail: TransactionDetail) -> String {
        let typeCode = detail.transaction.type.dbValue
        if typeCode == "transfer" {
            let from = detail.fromAccountName ?? "?"
            let to = detail.toAccountName ?? "?"
            return "\(from) → \(to)"
        }
        return detail.fromAccountName ?? detail.toAccountName ?? ""
    }

    private func iconName(_ detail: TransactionDetail) -> String {
        if detail.transaction.type.dbValue == "transfer" {
            return "arrow.left.arrow.right"
        }
        return LedgerIcon.symbol(for: detail.categoryIcon)
    }

    private func iconTint(_ detail: TransactionDetail, typeCode: String) -> Color {
        if typeCode == "transfer" { return SemanticColor.transfer }
        if let hex = detail.categoryColor { return Color(hex: hex) }
        return SemanticColor.forTransactionType(typeCode)
    }

    private func amountText(_ detail: TransactionDetail) -> String {
        switch detail.transaction.type.dbValue {
        case "expense": return "-" + Fmt.money(detail.transaction.amount)
        case "income": return "+" + Fmt.money(detail.transaction.amount)
        default: return Fmt.money(detail.transaction.amount)
        }
    }

    // ------------------------------------------------------------ 数据

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            let details = try await env.facade.detailsBetween(
                startMillis: request.fromMillis,
                endExclusiveMillis: request.toMillis,
                categoryId: request.categoryId,
                typeCode: request.typeCode
            )
            groups = TransactionListView.groupByDay(details)
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func delete(_ detail: TransactionDetail) async {
        do {
            _ = try await env.facade.deleteTransaction(
                transactionId: detail.transaction.transactionId,
                expectedUpdatedAtMillis: detail.transaction.updatedAt
            )
            message = "已删除，余额已回滚"
            await load()
            onChange()
        } catch {
            message = ledgerMessage(error)
        }
    }

    /// 按本地自然日分组，组内倒序。日界用本地时区（见类型注释）。
    static func groupByDay(_ details: [TransactionDetail]) -> [DayGroup] {
        var buckets: [String: [TransactionDetail]] = [:]
        var order: [String] = []

        for detail in details {
            let key = Fmt.date(detail.transaction.occurredAt)
            if buckets[key] == nil {
                buckets[key] = []
                order.append(key)
            }
            buckets[key]?.append(detail)
        }

        return order.sorted(by: >).map { key in
            let items = (buckets[key] ?? []).sorted {
                $0.transaction.occurredAt > $1.transaction.occurredAt
            }
            let expense = items
                .filter { $0.transaction.type.dbValue == "expense" }
                .reduce(Int64(0)) { $0 + $1.transaction.amount }
            let income = items
                .filter { $0.transaction.type.dbValue == "income" }
                .reduce(Int64(0)) { $0 + $1.transaction.amount }
            return DayGroup(
                id: key,
                dayLabel: Fmt.dayHeader(items.first?.transaction.occurredAt ?? 0),
                expenseCents: expense,
                incomeCents: income,
                items: items
            )
        }
    }
}

/// 明细页的一天。
struct DayGroup: Identifiable {
    let id: String
    let dayLabel: String
    let expenseCents: Int64
    let incomeCents: Int64
    let items: [TransactionDetail]
}
