import SwiftUI
import Shared

/// 语义色（与主题明暗无关的业务色），必须与 Android 侧 `LedgerSemanticColors` 完全一致。
///
/// 记账场景遵循中文用户习惯：**支出暖色、收入绿色**、转账中性蓝。
/// 注意这与股票涨跌的红绿方向相反——这里表达的是「流出 / 流入」。
enum SemanticColor {
    static let expense = Color(hex: "#D64B4B")
    static let income = Color(hex: "#2F9E68")
    static let transfer = Color(hex: "#3A7BD5")

    static let budgetNormal = Color(hex: "#2E7D6F")
    static let budgetWarning = Color(hex: "#E08A2B")
    static let budgetExceeded = Color(hex: "#D64B4B")

    static func forWarningLevel(_ level: Int32) -> Color {
        if level >= 2 { return budgetExceeded }
        if level == 1 { return budgetWarning }
        return budgetNormal
    }

    /// 流水类型 → 金额颜色
    static func forTransactionType(_ code: String) -> Color {
        switch code {
        case "expense": return expense
        case "income": return income
        default: return transfer
        }
    }
}

/// 品牌色（浅色 / 深色各一套），与 Android 侧 `Theme.kt` 的同名色值对齐。
enum BrandColor {
    static let primary = Color(light: "#1E6E60", dark: "#8ED3C5")
    static let onPrimary = Color(light: "#FFFFFF", dark: "#00382F")
    static let primaryContainer = Color(light: "#AAEFE0", dark: "#0B5145")
    static let onPrimaryContainer = Color(light: "#00201A", dark: "#AAEFE0")

    static let background = Color(light: "#FBF7F2", dark: "#121413")
    static let surface = Color(light: "#FBF7F2", dark: "#121413")
    static let surfaceContainer = Color(light: "#F0EDE8", dark: "#1B1F1E")
    static let surfaceContainerHigh = Color(light: "#EAE7E2", dark: "#252A28")
    static let onSurface = Color(light: "#191C1B", dark: "#E1E3E1")
    static let onSurfaceVariant = Color(light: "#3F4946", dark: "#BFC9C4")
    static let outline = Color(light: "#6F7975", dark: "#89938F")
    static let outlineVariant = Color(light: "#BFC9C4", dark: "#3F4946")
    static let error = Color(light: "#BA1A1A", dark: "#FFB4AB")
    static let errorContainer = Color(light: "#FFD6D2", dark: "#93000A")
    static let onErrorContainer = Color(light: "#410002", dark: "#FFDAD6")
    static let warningTint = Color(hex: "#E08A2B")
}

extension Color {

    /// 十六进制字符串（`#RRGGBB` / `#AARRGGBB`）→ Color。
    /// 分类/账户的颜色以字符串存库（见 `DefaultSeed`），双端各自解析。
    init(hex: String) {
        let raw = hex.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        var value: UInt64 = 0
        let normalized: String
        switch raw.count {
        case 6: normalized = "FF" + raw
        case 8: normalized = raw
        default:
            self = BrandColor.primary
            return
        }
        guard Scanner(string: normalized).scanHexInt64(&value) else {
            self = BrandColor.primary
            return
        }
        self.init(
            .sRGB,
            red: Double((value >> 16) & 0xFF) / 255.0,
            green: Double((value >> 8) & 0xFF) / 255.0,
            blue: Double(value & 0xFF) / 255.0,
            opacity: Double((value >> 24) & 0xFF) / 255.0
        )
    }

    /// 明暗两态各给一个色值（沿用 Android 侧 ColorScheme 的做法）。
    init(light: String, dark: String) {
        self.init(uiColor: UIColor { traits in
            UIColor(Color(hex: traits.userInterfaceStyle == .dark ? dark : light))
        })
    }
}

/// 卡片容器：圆角 + 容器色，双端观感统一。
struct LedgerCard<Content: View>: View {
    var title: String?
    var trailing: AnyView?
    @ViewBuilder var content: () -> Content

    init(
        _ title: String? = nil,
        trailing: AnyView? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.trailing = trailing
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title = title {
                HStack {
                    Text(title)
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(BrandColor.onSurfaceVariant)
                    Spacer()
                    if let trailing = trailing { trailing }
                }
                .padding(.horizontal, 4)
            }
            VStack(alignment: .leading, spacing: 10) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(BrandColor.surfaceContainer)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }
}

/// 圆形图标底（分类 / 账户头像）。
struct IconBadge: View {
    let systemName: String
    let tint: Color
    var size: CGFloat = 38

    var body: some View {
        ZStack {
            Circle().fill(tint.opacity(0.14))
            Image(systemName: systemName)
                .font(.system(size: size * 0.45, weight: .medium))
                .foregroundColor(tint)
        }
        .frame(width: size, height: size)
    }
}

/// 统一的一行列表项。
struct LedgerRow<Content: View, Trailing: View>: View {
    var leading: AnyView?
    @ViewBuilder var content: () -> Content
    @ViewBuilder var trailing: () -> Trailing

    init(
        leading: AnyView? = nil,
        @ViewBuilder content: @escaping () -> Content,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.leading = leading
        self.content = content
        self.trailing = trailing
    }

    var body: some View {
        HStack(spacing: 12) {
            if let leading = leading { leading }
            VStack(alignment: .leading, spacing: 2) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}
