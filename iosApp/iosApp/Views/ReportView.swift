import SwiftUI
import Shared

/// P11 报表（PRD §4.2 / §7.2 / §11.5）。
///
/// 口径（§7.2）：**只看 `type ∈ {expense, income}` 且未软删除**的流水；
/// `transfer` 不参与收支汇总、分类占比与趋势图，它只影响账户余额。
///
/// 周 / 月的边界、翻页语义全部由 Kotlin 侧计算（`IosReportBridge.kt`），
/// 这一层只持有 `PeriodInfo`（4 个标量）并在翻页时原样传回——
/// 避免 Swift 与 Kotlin 各写一套「周一起点」的日历逻辑，那是最容易悄悄漂移的地方。
struct ReportView: View {
    @EnvironmentObject private var env: AppEnvironment

    /// 报表节点 → 明细下钻
    let onDrill: (TransactionListRequest) -> Void
    let refreshToken: Int

    @State private var granularityCode = "month"
    @State private var info: PeriodInfo?
    @State private var data: ReportData?
    @State private var loading = true
    @State private var message: String?

    var body: some View {
        Group {
            if loading && data == nil {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    if let message = message {
                        Section { MessageBanner(text: message) }
                    }

                    Section {
                        periodSelector
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }

                    summarySection
                    shareSection
                    trendSection

                    Section {
                        Text("转账不计入收支汇总与趋势；删除的流水不计入。")
                            .font(.caption)
                            .foregroundColor(BrandColor.onSurfaceVariant)
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("报表")
        .navigationBarTitleDisplayMode(.inline)
        .task { await bootstrap() }
        .task(id: refreshToken) { await reload() }
    }

    // ------------------------------------------------------------ 周期选择

    private var periodSelector: some View {
        VStack(spacing: 10) {
            Picker("粒度", selection: $granularityCode) {
                Text("按周").tag("week")
                Text("按月").tag("month")
            }
            .pickerStyle(.segmented)
            .onChange(of: granularityCode) { _ in
                Task { await resetToLatest() }
            }

            HStack {
                Button {
                    Task { await shift(-1) }
                } label: {
                    Label("上一期", systemImage: "chevron.left")
                        .font(.subheadline)
                }
                .buttonStyle(.plain)

                Spacer()

                Text(info?.label ?? "")
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(BrandColor.onSurface)

                Spacer()

                Button {
                    Task { await shift(1) }
                } label: {
                    Label("下一期", systemImage: "chevron.right")
                        .labelStyle(.titleAndIcon)
                        .font(.subheadline)
                }
                .buttonStyle(.plain)
                .disabled(atLatest)
                .opacity(atLatest ? 0.35 : 1)
            }
        }
    }

    /// 已是「当前周 / 当前月」时不能再往后翻（PRD §4.2 P11）
    private var atLatest: Bool {
        guard let info = info else { return true }
        let now = env.facade.nowUtcMillis()
        let current = IosReportBridgeKt.currentPeriodInfo(
            granularityCode: granularityCode,
            nowMillis: now
        )
        return info.endExclusiveMillis >= current.endExclusiveMillis
    }

    // ------------------------------------------------------------ 汇总

    private var summarySection: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Text("结余")
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onPrimaryContainer.opacity(0.75))
                Text(Fmt.signedMoney(data?.summary.balanceCents ?? 0))
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundColor(BrandColor.onPrimaryContainer)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)

                HStack(spacing: 24) {
                    statColumn("收入", data?.summary.incomeCents ?? 0, SemanticColor.income)
                    statColumn("支出", data?.summary.expenseCents ?? 0, SemanticColor.expense)
                    Spacer()
                    Button("看明细") {
                        guard let info = info else { return }
                        onDrill(
                            TransactionListRequest(
                                fromMillis: info.startMillis,
                                toMillis: info.endExclusiveMillis,
                                categoryId: nil,
                                typeCode: nil,
                                title: "\(info.label)流水"
                            )
                        )
                    }
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onPrimaryContainer)
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(BrandColor.primaryContainer)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
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

    // ------------------------------------------------------------ 支出构成

    private var shareSection: some View {
        let shares = (data?.expenseShares ?? []).enumerated().map { index, share in
            ChartSlice(
                id: share.categoryId ?? "unknown-\(index)",
                label: share.name,
                color: Color(hex: share.color),
                amountCents: share.amountCents,
                ratio: share.ratio,
                categoryId: share.categoryId
            )
        }

        return Section("支出构成") {
            if shares.isEmpty {
                Text("这一期还没有支出")
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            } else {
                VStack(spacing: 14) {
                    DonutChart(
                        slices: shares,
                        centerLabel: "总支出",
                        centerValue: Fmt.money(data?.summary.expenseCents ?? 0),
                        diameter: 200
                    ) { categoryId in
                        drillToCategory(categoryId)
                    }

                    ForEach(shares.prefix(6)) { slice in
                        ShareLegendRow(slice: slice) {
                            drillToCategory(slice.categoryId)
                        }
                    }

                    if shares.count > 6 {
                        Text("仅显示前 6 项，共 \(shares.count) 个分类")
                            .font(.caption2)
                            .foregroundColor(BrandColor.onSurfaceVariant)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func drillToCategory(_ categoryId: String?) {
        guard let info = info, let categoryId = categoryId else { return }
        onDrill(
            TransactionListRequest(
                fromMillis: info.startMillis,
                toMillis: info.endExclusiveMillis,
                categoryId: categoryId,
                // 分类占比只统计支出维度（PRD §7.2），下钻也只按支出查
                typeCode: "expense",
                title: "分类明细"
            )
        )
    }

    // ------------------------------------------------------------ 收支趋势

    private var trendSection: some View {
        Section("收支趋势") {
            let points = data?.trend ?? []
            if points.isEmpty {
                Text("这一期还没有数据")
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    TrendChart(points: points, onPointClick: { index in
                        guard index < points.count else { return }
                        drillToDay(points[index])
                    })
                    .frame(height: 160)

                    HStack(spacing: 16) {
                        legend(color: SemanticColor.expense, text: "支出")
                        legend(color: SemanticColor.income, text: "收入")
                        Spacer()
                        Text("点一下柱子看当天明细")
                            .font(.caption2)
                            .foregroundColor(BrandColor.onSurfaceVariant)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func legend(color: Color, text: String) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text)
                .font(.caption)
                .foregroundColor(BrandColor.onSurfaceVariant)
        }
    }

    private func drillToDay(_ point: TrendPoint) {
        let dayStart = point.bucketStartMillis
        // 趋势桶是「本地自然日 00:00」，下钻区间取同日 24 小时
        onDrill(
            TransactionListRequest(
                fromMillis: dayStart,
                toMillis: dayStart + 24 * 60 * 60 * 1000,
                categoryId: nil,
                typeCode: nil,
                title: "\(point.label) 明细"
            )
        )
    }

    // ------------------------------------------------------------ 数据

    private func bootstrap() async {
        if info == nil {
            info = IosReportBridgeKt.currentPeriodInfo(
                granularityCode: granularityCode,
                nowMillis: env.facade.nowUtcMillis()
            )
        }
        await reload()
    }

    private func resetToLatest() async {
        info = IosReportBridgeKt.currentPeriodInfo(
            granularityCode: granularityCode,
            nowMillis: env.facade.nowUtcMillis()
        )
        await reload()
    }

    private func shift(_ steps: Int32) async {
        guard let current = info else { return }
        // 不能翻到未来（PRD §4.2 P11 的「下一期」在当前期禁用）
        if steps > 0 && atLatest { return }
        info = IosReportBridgeKt.shiftPeriodInfo(info: current, steps: steps)
        await reload()
    }

    private func reload() async {
        guard let info = info else { return }
        loading = true
        defer { loading = false }
        do {
            data = try await env.facade.reportOf(info: info)
            message = nil
        } catch {
            message = ledgerMessage(error)
        }
    }
}

// ============================================================================
// 图表
// ============================================================================

struct ChartSlice: Identifiable {
    let id: String
    let label: String
    let color: Color
    let amountCents: Int64
    let ratio: Double
    let categoryId: String?
}

/// 环形图（支出构成）。
///
/// 用 `Circle().trim(from:to:)` 而不是 `Canvas`：trim 是 SwiftUI 原生的路径裁剪，
/// 每片独立成层，颜色一次给足、不需要手工拼弧线；命中检测则单独用 `DragGesture`
/// 在极坐标里判（因为 trim 出来的形状不可点）。
///
/// 角度约定：`trim` 的 0 在 3 点方向、顺时针增长，所以整体旋转 -90°，
/// 让第一片从 12 点方向开始——这也是双端一致的视觉约定。
struct DonutChart: View {
    let slices: [ChartSlice]
    let centerLabel: String
    let centerValue: String
    var diameter: CGFloat = 200
    var onSliceClick: ((String?) -> Void)?

    /// 环宽占半径的 20%
    private var ringWidth: CGFloat { diameter * 0.1 }
    private var total: Double { slices.reduce(0) { $0 + $1.ratio } }

    var body: some View {
        ZStack {
            // 只旋转圆环本身：中心文字必须保持水平
            ZStack {
                Circle()
                    .stroke(BrandColor.surfaceContainerHigh, lineWidth: ringWidth)

                ForEach(bounds(), id: \.index) { bound in
                    Circle()
                        .trim(from: bound.from, to: bound.to)
                        .stroke(
                            slices[bound.index].color,
                            style: StrokeStyle(lineWidth: ringWidth, lineCap: .butt)
                        )
                }
            }
            .rotationEffect(.degrees(-90))

            VStack(spacing: 2) {
                Text(centerLabel)
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
                Text(centerValue)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(BrandColor.onSurface)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
            }
            .padding(.horizontal, diameter * 0.2)
        }
        .frame(width: diameter, height: diameter)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0).onEnded { value in
                handleTap(at: value.location)
            }
        )
    }

    /// 每片的 trim 区间，顺带留 0.4% 的视觉缝隙（约 1.4°）
    private func bounds() -> [(index: Int, from: CGFloat, to: CGFloat)] {
        guard total > 0 else { return [] }
        let gap: CGFloat = 0.004
        var cursor = 0.0
        var result: [(index: Int, from: CGFloat, to: CGFloat)] = []
        for (index, slice) in slices.enumerated() {
            let width = slice.ratio / total
            let from = cursor + Double(gap) / 2
            let to = cursor + width - Double(gap) / 2
            if to > from {
                result.append((index: index, from: CGFloat(from), to: CGFloat(to)))
            }
            cursor += width
        }
        return result
    }

    /// 极坐标命中检测：先判半径是否落在环带上，再把触点角度换算回「12 点起、顺时针」的比例。
    private func handleTap(at location: CGPoint) {
        guard let onSliceClick = onSliceClick, total > 0 else { return }
        let radius = diameter / 2
        let dx = location.x - radius
        let dy = location.y - radius
        let distance = (dx * dx + dy * dy).squareRoot()
        guard distance >= radius - ringWidth, distance <= radius else { return }

        // 视图整体旋转了 -90°，命中检测要在「未旋转」的坐标系里做：
        // 因此把 atan2 的角度整体 +90° 补偿回去。
        var degrees = atan2(Double(dy), Double(dx)) * 180.0 / Double.pi
        degrees = (degrees + 90 + 360).truncatingRemainder(dividingBy: 360)
        let fraction = degrees / 360.0

        var cursor = 0.0
        for slice in slices {
            let width = slice.ratio / total
            if fraction >= cursor && fraction < cursor + width {
                onSliceClick(slice.categoryId)
                return
            }
            cursor += width
        }
    }
}

/// 图例行（点击等同于点环形图对应扇形）
struct ShareLegendRow: View {
    let slice: ChartSlice
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                Circle().fill(slice.color).frame(width: 10, height: 10)
                Text(slice.label)
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurface)
                    .lineLimit(1)
                Spacer()
                Text(Fmt.money(slice.amountCents))
                    .font(.subheadline)
                    .foregroundColor(BrandColor.onSurface)
                Text(Fmt.percent(slice.ratio))
                    .font(.caption)
                    .foregroundColor(BrandColor.onSurfaceVariant)
                    .frame(width: 44, alignment: .trailing)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// 收支趋势（按日聚合的柱状图）。
///
/// 用「每天一根支出柱 + 一条收入柱」而不是双折线：MVP 的周期最多 31 天，
/// 柱状在窄屏上更容易比较「哪天花得多」，也更适合做点击下钻——
/// 每根柱子的命中区就是一个等宽的透明列，不需要做折线的最近点检索。
struct TrendChart: View {
    let points: [TrendPoint]
    var onPointClick: ((Int) -> Void)?

    private var maxValue: Double {
        let peak = points.reduce(Int64(0)) { acc, point in
            max(acc, max(point.expenseCents, point.incomeCents))
        }
        return Double(max(peak, 1))
    }

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            // 底部留 16pt 给 x 轴标签
            let chartHeight = max(geo.size.height - 16, 40)

            VStack(spacing: 2) {
                ZStack(alignment: .bottom) {
                    // 基线
                    Rectangle()
                        .fill(BrandColor.outlineVariant)
                        .frame(height: 0.5)

                    HStack(alignment: .bottom, spacing: 2) {
                        ForEach(Array(points.enumerated()), id: \.offset) { _, point in
                            column(point: point, height: chartHeight)
                                .frame(height: chartHeight)
                        }
                    }

                    // 命中层：每列等宽的透明区域，点一下就是「那一天」
                    HStack(spacing: 0) {
                        ForEach(Array(points.enumerated()), id: \.offset) { index, _ in
                            Color.clear
                                .contentShape(Rectangle())
                                .onTapGesture { onPointClick?(index) }
                        }
                    }
                }
                .frame(height: chartHeight)

                // x 轴标签：只在首 / 中 / 尾显示，避免挤成一团
                HStack {
                    Text(points.first?.label ?? "")
                        .font(.caption2)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                    Spacer()
                    if points.count > 2 {
                        Text(points[points.count / 2].label)
                            .font(.caption2)
                            .foregroundColor(BrandColor.onSurfaceVariant)
                    }
                    Spacer()
                    Text(points.last?.label ?? "")
                        .font(.caption2)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                }
                .frame(width: width)
            }
        }
    }

    private func column(point: TrendPoint, height: CGFloat) -> some View {
        // 上下各留 16pt 给标签与留白
        let usable = max(height - 28, 10)
        let expenseHeight = CGFloat(Double(point.expenseCents) / maxValue) * usable
        let incomeHeight = CGFloat(Double(point.incomeCents) / maxValue) * usable

        return VStack(spacing: 1) {
            Spacer(minLength: 0)
            if point.incomeCents > 0 {
                RoundedRectangle(cornerRadius: 2)
                    .fill(SemanticColor.income)
                    .frame(height: max(incomeHeight, 2))
            }
            if point.expenseCents > 0 {
                RoundedRectangle(cornerRadius: 2)
                    .fill(SemanticColor.expense)
                    .frame(height: max(expenseHeight, 2))
            }
        }
        .frame(maxWidth: .infinity, alignment: .bottom)
    }
}
