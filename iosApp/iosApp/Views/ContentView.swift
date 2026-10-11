import SwiftUI
import Shared

// ============================================================================
// 根视图：启动页 → 三 Tab 主界面（PRD §4.1）
// ============================================================================

struct RootView: View {
    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        ZStack {
            BrandColor.background.ignoresSafeArea()

            if let error = env.startupError {
                StartupErrorView(message: error) {
                    Task { await env.bootstrap() }
                }
            } else if !env.ready {
                SplashView()
            } else {
                ContentView()
            }
        }
    }
}

/// P01 启动页。真正的职责是**等启动引导完成**（打开 SQLCipher、写入预置分类/账户），
/// 而不是只展示 Logo——否则首页第一次查询会撞上「表还没建」。
private struct SplashView: View {
    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(BrandColor.primary)
                    .frame(width: 88, height: 88)
                Image(systemName: "wallet.pass.fill")
                    .font(.system(size: 40, weight: .medium))
                    .foregroundColor(BrandColor.onPrimary)
            }
            Text("记账")
                .font(.largeTitle.weight(.semibold))
                .foregroundColor(BrandColor.onSurface)
            Text("3 步记一笔，账本自己算")
                .font(.subheadline)
                .foregroundColor(BrandColor.onSurfaceVariant)

            ProgressView().padding(.top, 16)
            Text("正在解锁本地账本…")
                .font(.caption)
                .foregroundColor(BrandColor.onSurfaceVariant)
        }
        .padding(32)
    }
}

private struct StartupErrorView: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 40))
                .foregroundColor(BrandColor.error)
            Text("启动失败：\(message)")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundColor(BrandColor.onSurface)
            Button("重试", action: onRetry)
                .buttonStyle(.borderedProminent)
                .tint(BrandColor.primary)
        }
        .padding(32)
    }
}

// ============================================================================
// 导航目标（用 Identifiable 让 sheet / NavigationLink 消费）
// ============================================================================

struct RecordTarget: Identifiable {
    let id = UUID()
    /// nil = 新增（P03），非 nil = 编辑（P04）
    var transactionId: String?
}

struct TransactionListRequest: Identifiable {
    let id = UUID()
    var fromMillis: Int64
    var toMillis: Int64
    var categoryId: String?
    var typeCode: String?
    var title: String

    static func all() -> TransactionListRequest {
        TransactionListRequest(
            fromMillis: 0,
            toMillis: Int64.max,
            categoryId: nil,
            typeCode: nil,
            title: "全部流水"
        )
    }
}

// ============================================================================
// 主界面：三 Tab + 全局页
// ============================================================================

struct ContentView: View {
    @EnvironmentObject private var env: AppEnvironment
    @EnvironmentObject private var quickActions: QuickActionBus

    @State private var tab = 0
    @State private var recordTarget: RecordTarget?
    @State private var listRequest: TransactionListRequest?
    /// 记账保存/删除后自增，让首页与报表知道该重算
    @State private var refreshToken = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationView {
                HomeView(
                    onRecord: { recordTarget = RecordTarget(transactionId: nil) },
                    onOpenList: { listRequest = .all() },
                    onOpenBudget: { tab = 2 },
                    onChange: { refreshToken += 1 },
                    refreshToken: refreshToken
                )
            }
            .navigationViewStyle(.stack)
            .tabItem { Label("首页", systemImage: "house.fill") }
            .tag(0)

            NavigationView {
                ReportView(
                    onDrill: { request in listRequest = request },
                    refreshToken: refreshToken
                )
            }
            .navigationViewStyle(.stack)
            .tabItem { Label("报表", systemImage: "chart.bar.fill") }
            .tag(1)

            NavigationView {
                SettingsHubView(
                    onChange: { refreshToken += 1 },
                    refreshToken: refreshToken
                )
            }
            .navigationViewStyle(.stack)
            .tabItem { Label("设置", systemImage: "gearshape.fill") }
            .tag(2)
        }
        .background(
            // 明细页下钻：用隐藏的 NavigationLink 把 listRequest 变成一次 push
            NavigationLink(
                isActive: Binding(
                    get: { listRequest != nil },
                    set: { if !$0 { listRequest = nil } }
                ),
                destination: {
                    if let request = listRequest {
                        TransactionListView(
                            request: request,
                            onChange: { refreshToken += 1 }
                        )
                    } else {
                        EmptyView()
                    }
                },
                label: { EmptyView() }
            )
        )
        .sheet(item: $recordTarget) { target in
            RecordView(
                transactionId: target.transactionId,
                onDone: {
                    recordTarget = nil
                    refreshToken += 1
                }
            )
            .environmentObject(env)
        }
        .onChange(of: quickActions.pendingRecord) { pending in
            if pending {
                recordTarget = RecordTarget(transactionId: nil)
                quickActions.consume()
            }
        }
    }
}

// ============================================================================
// 通用小组件
// ============================================================================

struct EmptyHint: View {
    let text: String
    var actionLabel: String?
    var onAction: (() -> Void)?

    var body: some View {
        VStack(spacing: 12) {
            Text(text)
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundColor(BrandColor.onSurfaceVariant)
            if let actionLabel = actionLabel, let onAction = onAction {
                Button(actionLabel, action: onAction).font(.subheadline)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
    }
}

/// 二次确认弹窗统一走 `.alert`，文案与 Android 侧保持一致。
struct ConfirmSpec: Identifiable {
    let id = UUID()
    var title: String
    var message: String
    var confirmLabel: String = "删除"
    var destructive: Bool = true
    var onConfirm: () -> Void
}

struct MessageBanner: View {
    let text: String?

    var body: some View {
        if let text = text, !text.isEmpty {
            Text(text)
                .font(.footnote)
                .foregroundColor(BrandColor.onPrimaryContainer)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(BrandColor.primaryContainer)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.horizontal, 16)
                .transition(.opacity)
        }
    }
}

/// 金额大字（首页 / 报表汇总用）。
struct AmountText: View {
    let cents: Int64
    var signed = false
    var font: Font = .system(size: 34, weight: .semibold)

    var body: some View {
        Text(signed ? Fmt.signedMoney(cents) : Fmt.money(cents))
            .font(font)
            .minimumScaleFactor(0.6)
            .lineLimit(1)
    }
}
