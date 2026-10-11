import SwiftUI
import Shared

/// P14 外观模式三态。
///
/// 存的是字符串 code（`system` / `light` / `dark`），与 Kotlin 侧 `PreferenceKeys` 一致，
/// 通过 `IosUiBridgeKt.prefValueThemeXxx()` 取常量，避免双端各写一份字面量。
enum ThemeMode: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: return "跟随系统"
        case .light: return "浅色"
        case .dark: return "深色"
        }
    }

    /// 传给 `.preferredColorScheme`；nil = 跟随系统
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }

    var prefValue: String {
        switch self {
        case .system: return IosUiBridgeKt.prefValueThemeSystem()
        case .light: return IosUiBridgeKt.prefValueThemeLight()
        case .dark: return IosUiBridgeKt.prefValueThemeDark()
        }
    }

    static func from(pref: String?) -> ThemeMode {
        guard let pref = pref else { return .system }
        if pref == IosUiBridgeKt.prefValueThemeLight() { return .light }
        if pref == IosUiBridgeKt.prefValueThemeDark() { return .dark }
        return .system
    }
}

/// 应用级状态：依赖门面 + 启动进度 + 外观模式。
///
/// 界面偏好走 `PreferencePort`（iOS 侧是 `NSUserDefaults`），真实秘密（DB Key）在 Keychain。
@MainActor
final class AppEnvironment: ObservableObject {

    let facade: LedgerFacade

    @Published private(set) var ready = false
    @Published var startupError: String?
    @Published var themeMode: ThemeMode

    init(facade: LedgerFacade) {
        self.facade = facade
        let stored = facade.preference.getString(key: IosUiBridgeKt.prefKeyThemeMode())
        self.themeMode = ThemeMode.from(pref: stored)
    }

    /// 启动引导：打开加密库并写入预置分类/账户（幂等）。
    /// 放在 P01 启动页里 await —— 首页第一次查询时表一定已经建好。
    func bootstrap() async {
        guard !ready else { return }
        do {
            try await facade.initialize()
            startupError = nil
            ready = true
        } catch {
            startupError = error.localizedDescription
        }
    }

    func setThemeMode(_ mode: ThemeMode) {
        themeMode = mode
        facade.preference.putString(
            key: IosUiBridgeKt.prefKeyThemeMode(),
            value: mode.prefValue
        )
    }

    /// 记账保存后触发预算阈值检查（PRD §7.3）。
    func checkBudgetNotification() {
        Task { try? await facade.checkBudgetNotification() }
    }
}
