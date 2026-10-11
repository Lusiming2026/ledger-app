import SwiftUI
import Shared

/// 展示层格式化：**全部转调 Kotlin 侧实现**，Swift 不自己算。
///
/// 原因：金额是 Int64「分」，格式化与解析都必须保持「整数运算、不用浮点」
/// （PRD §11.6 / R-AMT-03）。两处实现意味着两处可能漂移，因此唯一实现放在
/// `IosUiBridge.kt`（与 Android 侧共用同一份领域模型）。
enum Fmt {

    static func money(_ cents: Int64) -> String { IosUiBridgeKt.moneyText(cents: cents) }

    static func signedMoney(_ cents: Int64) -> String { IosUiBridgeKt.signedMoneyText(cents: cents) }

    /// 输入框原始文本（无 ¥ 与千分位）
    static func plainYuan(_ cents: Int64) -> String { IosUiBridgeKt.plainYuanText(cents: cents) }

    static func date(_ millis: Int64) -> String { IosUiBridgeKt.dateText(millis: millis) }

    static func dateTime(_ millis: Int64) -> String { IosUiBridgeKt.dateTimeText(millis: millis) }

    static func dayHeader(_ millis: Int64) -> String { IosUiBridgeKt.dayHeaderText(millis: millis) }

    static func shortDate(_ millis: Int64) -> String { IosUiBridgeKt.shortDateText(millis: millis) }

    static func percent(_ ratio: Double) -> String { IosUiBridgeKt.percentText(ratio: ratio) }

    static func fileSize(_ bytes: Int32) -> String { IosUiBridgeKt.fileSizeText(bytes: bytes) }

    /// 解析用户输入的「元」→「分」；非法输入返回 nil。
    static func parseAmount(_ text: String) -> Int64? {
        let result = IosUiBridgeKt.parseAmountText(text: text)
        return result.ok ? result.cents : nil
    }

    // ---- ID 生成（PRD §7.7：客户端生成 UUID 作幂等键） ----

    static func newTransactionId() -> String { IosUiBridgeKt.newTransactionId() }
    static func newAccountId() -> String { IosUiBridgeKt.newAccountId() }
    static func newCategoryId() -> String { IosUiBridgeKt.newCategoryId() }
    static func newBudgetId() -> String { IosUiBridgeKt.newBudgetId() }
    static func newReconciliationId() -> String { IosUiBridgeKt.newReconciliationId() }
}

/// 分类图标名（`DefaultSeed` 里的语义短名）→ SF Symbols。
///
/// 库里存的是字符串名而不是资源 ID，这样备份文件跨端恢复时不会失效；
/// 每个平台自己把名字映射到本地图标——这里就是 iOS 侧的映射表。
enum LedgerIcon {

    private static let map: [String: String] = [
        "restaurant": "fork.knife",
        "directions_bus": "bus.fill",
        "shopping_bag": "bag.fill",
        "sports_esports": "gamecontroller.fill",
        "menu_book": "book.fill",
        "home": "house.fill",
        "smartphone": "iphone",
        "local_hospital": "cross.case.fill",
        "card_giftcard": "gift.fill",
        "more_horiz": "ellipsis.circle.fill",
        "account_balance_wallet": "wallet.pass.fill",
        "work": "briefcase.fill",
        "school": "graduationcap.fill",
        "redeem": "giftcard.fill",
        "receipt_long": "list.bullet.rectangle",
        "pie_chart": "chart.pie.fill",
    ]

    /// P06 图标选择器：种子名 + SF Symbol，顺序即展示顺序。
    static let presets: [(name: String, symbol: String)] = [
        ("restaurant", "fork.knife"),
        ("directions_bus", "bus.fill"),
        ("shopping_bag", "bag.fill"),
        ("sports_esports", "gamecontroller.fill"),
        ("menu_book", "book.fill"),
        ("home", "house.fill"),
        ("smartphone", "iphone"),
        ("local_hospital", "cross.case.fill"),
        ("card_giftcard", "gift.fill"),
        ("more_horiz", "ellipsis.circle.fill"),
        ("account_balance_wallet", "wallet.pass.fill"),
        ("work", "briefcase.fill"),
        ("school", "graduationcap.fill"),
        ("redeem", "giftcard.fill"),
        ("receipt_long", "list.bullet.rectangle"),
        ("pie_chart", "chart.pie.fill"),
    ]

    /// P06 颜色选择器：与 Android 侧 `LedgerIcons.colorPresets` 同一批色号。
    static let colorPresets: [String] = [
        "#FF7043", "#EF5350", "#EC407A", "#AB47BC", "#7E57C2",
        "#42A5F5", "#29B6F6", "#26A69A", "#66BB6A", "#9CCC65",
        "#FFA726", "#FFCA28", "#8D6E63", "#78909C",
    ]

    static func symbol(for name: String?) -> String {
        guard let name = name, let symbol = map[name] else { return "square.grid.2x2.fill" }
        return symbol
    }

    static func accountSymbol(typeCode: String) -> String {
        switch typeCode {
        case "cash": return "banknote.fill"
        case "bank_card": return "creditcard.fill"
        case "wechat": return "message.fill"
        case "alipay": return "building.columns.fill"
        default: return "banknote.fill"
        }
    }
}
