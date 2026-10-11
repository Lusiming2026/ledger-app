import SwiftUI
import Shared

/// 应用入口。
///
/// 启动顺序与 Android 侧一致（见 `LedgerApplication.kt`）：
///   1. 装 Koin 依赖图，四个平台端口由 Swift 实现（Keychain / CryptoKit / DocumentPicker / UNUserNotificationCenter）；
///   2. 用 `LedgerFacade` 包一层，避免 Swift 侧接触 Koin 的 KClass 与 Kotlin Flow 协议；
///   3. 启动引导（打开 SQLCipher 库 + 幂等补齐预置分类/账户）在 `AppEnvironment.bootstrap()` 里 await。
///
/// 加密库说明：`shared/build.gradle.kts` 里 `linkSqlite = false`，
/// 系统 SQLite 不认 `PRAGMA key`，必须由 `Podfile` 的 `pod 'SQLCipher'` 在链接阶段提供符号。
@main
struct LedgerApp: App {

    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var env: AppEnvironment
    @ObservedObject private var quickActions = QuickActionBus.shared

    init() {
        let koin = IosPlatformKt.doInitKoin(
            keyStore: IosKeyStorePort(),
            crypto: IosCryptoPort(),
            filePicker: IosFilePickerPort(),
            notificationScheduler: IosNotificationPort()
        )
        let facade = LedgerFacadeKt.ledgerFacade(koin: koin)
        _env = StateObject(wrappedValue: AppEnvironment(facade: facade))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(env)
                .environmentObject(quickActions)
                // 外观三态（跟随系统 / 浅色 / 深色）即时生效：P14
                .preferredColorScheme(env.themeMode.colorScheme)
                .task { await env.bootstrap() }
        }
    }
}

/// 桌面快捷方式「记一笔」（Info.plist 的 `UIApplicationShortcutItems`）。
///
/// 只在 App 已运行时（`performActionFor`）生效；冷启动走的是 launch options，
/// SwiftUI 的 App 生命周期拿不到，这种情况会正常落到首页——已在 README 中标注。
final class QuickActionBus: ObservableObject {
    static let shared = QuickActionBus()
    @Published var pendingRecord = false

    func requestRecord() { pendingRecord = true }
    func consume() { pendingRecord = false }

    private init() {}
}

final class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        if shortcutItem.type == "com.ledger.ios.record" {
            QuickActionBus.shared.requestRecord()
            completionHandler(true)
        } else {
            completionHandler(false)
        }
    }
}
