import Foundation
import Shared
import UserNotifications

/// `NotificationSchedulerPort` 的 iOS 实现（T21）。
///
/// 幂等说明：本类**不做**「是否通知过」的判断——那是 `budget_notification` 表
/// （唯一索引 `uq_notify_once`）的职责（PRD §7.3 / §11.4）。这里只负责投递。
///
/// 权限被拒时的兜底（PRD §11.4）：不发系统通知，改由首页/预算页的进度条高亮预警，
/// 因此 [postBudgetAlert] 在无权限时静默返回，不抛异常。
final class IosNotificationPort: NSObject, NotificationSchedulerPort {

    private let center = UNUserNotificationCenter.current()

    override init() {
        super.init()
        // 前台也展示横幅，否则记账后立刻看首页时看不到预算提醒
        center.delegate = self
    }

    func hasPermission() -> Bool {
        // UNUserNotificationCenter 的授权查询是异步的，而端口是同步签名。
        // 这里用一个被异步刷新、初值保守为 false 的缓存：
        // 首次调用会返回 false（等价于「还没拿到权限」），随后由 refreshPermission 更新。
        var granted = cachedGranted
        if !hasRefreshed {
            hasRefreshed = true
            refreshPermission { granted = $0 }
        }
        return granted
    }

    func requestPermission(onResult: @escaping (Bool) -> Void) {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            self.cachedGranted = granted
            DispatchQueue.main.async { onResult(granted) }
        }
    }

    func postBudgetAlert(
        budgetId: String,
        threshold: Int32,
        periodKey: String,
        title: String,
        body: String
    ) {
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized ||
                settings.authorizationStatus == .provisional
            else {
                // 无权限：静默降级到 App 内进度条预警
                return
            }

            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default

            let request = UNNotificationRequest(
                // 同一 (预算, 阈值, 周期) 用同一个 identifier：即使上层重复投递，
                // 系统也只会保留一条，不会刷屏。
                identifier: "budget-\(budgetId)-\(threshold)-\(periodKey)",
                content: content,
                trigger: nil // nil = 立即投递
            )
            self.center.add(request)
        }
    }

    // MARK: - 内部

    private var cachedGranted = false
    private var hasRefreshed = false

    private func refreshPermission(_ completion: @escaping (Bool) -> Void) {
        center.getNotificationSettings { settings in
            let granted = settings.authorizationStatus == .authorized ||
                settings.authorizationStatus == .provisional
            self.cachedGranted = granted
            DispatchQueue.main.async { completion(granted) }
        }
    }
}

extension IosNotificationPort: UNUserNotificationCenterDelegate {

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}
