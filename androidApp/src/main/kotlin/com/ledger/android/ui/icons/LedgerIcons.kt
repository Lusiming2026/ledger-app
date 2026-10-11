package com.ledger.android.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.ledger.domain.model.AccountType
import com.ledger.domain.model.TransactionType

/**
 * 图标映射（PRD §6.5 / §4.2 P06）。
 *
 * 为什么用「字符串名」而不是直接存资源 ID：分类可以随备份文件跨端、跨设备恢复，
 * 存 Android 资源 ID 换到 iOS 或换台设备就失效了。因此库里存的是**语义短名**
 * （`restaurant` / `directions_bus` …，见 `DefaultSeed`），由各端映射到本地图标。
 * 这里就是 Android 侧的映射表。
 */
object LedgerIcons {

    /** 预置分类用到的图标名 → 图标 */
    private val CATEGORY_ICONS: Map<String, ImageVector> = mapOf(
        "restaurant" to Icons.Filled.Restaurant,
        "directions_bus" to Icons.Filled.DirectionsBus,
        "shopping_bag" to Icons.Filled.ShoppingBag,
        "sports_esports" to Icons.Filled.SportsEsports,
        "menu_book" to Icons.Filled.MenuBook,
        "home" to Icons.Filled.Home,
        "smartphone" to Icons.Filled.Smartphone,
        "local_hospital" to Icons.Filled.LocalHospital,
        "card_giftcard" to Icons.Filled.CardGiftcard,
        "more_horiz" to Icons.Filled.MoreHoriz,
        "account_balance_wallet" to Icons.Filled.AccountBalanceWallet,
        "work" to Icons.Filled.Work,
        "school" to Icons.Filled.School,
        "redeem" to Icons.Filled.Redeem,
        "receipt_long" to Icons.Filled.ReceiptLong,
        "pie_chart" to Icons.Filled.PieChart,
    )

    /** 未知图标名的兜底，避免恢复陌生备份时崩溃或出现空白。 */
    val FallbackCategory: ImageVector = Icons.Filled.Category

    fun category(name: String?): ImageVector = CATEGORY_ICONS[name] ?: FallbackCategory

    /**
     * P06 图标选择器：种子名与图标一一对应，顺序即展示顺序。
     * 只暴露 [CATEGORY_ICONS] 的键，保证「选出来的名字」一定能被 [category] 解析。
     */
    val categoryPresets: List<Pair<String, ImageVector>> =
        CATEGORY_ICONS.entries.map { it.key to it.value }

    /** P06 颜色选择器：与 `DefaultSeed` 的调色板保持同一批色号。 */
    val colorPresets: List<String> = listOf(
        "#FF7043", "#EF5350", "#EC407A", "#AB47BC", "#7E57C2",
        "#42A5F5", "#29B6F6", "#26A69A", "#66BB6A", "#9CCC65",
        "#FFA726", "#FFCA28", "#8D6E63", "#78909C",
    )

    fun accountType(type: AccountType): ImageVector = when (type) {
        AccountType.CASH -> Icons.Filled.Payments
        AccountType.BANK_CARD -> Icons.Filled.CreditCard
        AccountType.WECHAT -> Icons.Filled.Smartphone
        AccountType.ALIPAY -> Icons.Filled.AccountBalance
    }

    /**
     * 流水类型图标。刻意用**垂直**箭头而非 `TrendingUp/Down`：
     * 垂直方向不会因 RTL 被镜像，语义（流出/流入）在任何语言下都成立。
     */
    fun transactionType(type: TransactionType): ImageVector = when (type) {
        TransactionType.EXPENSE -> Icons.Filled.ArrowDownward
        TransactionType.INCOME -> Icons.Filled.ArrowUpward
        TransactionType.TRANSFER -> Icons.Filled.SwapVert
    }

    // ---- 固定图标（避免各页各写一个，风格漂移） ----

    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Forward: ImageVector = Icons.AutoMirrored.Filled.ArrowForward
    val ListIcon: ImageVector = Icons.AutoMirrored.Filled.List
    val HomeIcon: ImageVector = Icons.Filled.Home
    val Report: ImageVector = Icons.Filled.BarChart
    val Settings: ImageVector = Icons.Filled.Settings
    val Add: ImageVector = Icons.Filled.Add
    val Edit: ImageVector = Icons.Filled.Edit
    val Delete: ImageVector = Icons.Filled.Delete
    val Archive: ImageVector = Icons.Filled.Archive
    val Unarchive: ImageVector = Icons.Filled.Unarchive
    val Calendar: ImageVector = Icons.Filled.CalendarMonth
    val DragHandleIcon: ImageVector = Icons.Filled.DragHandle
    val Export: ImageVector = Icons.Filled.FileUpload
    val Import: ImageVector = Icons.Filled.FileDownload
    val Lock: ImageVector = Icons.Filled.Lock
    val Appearance: ImageVector = Icons.Filled.DarkMode
    val Info: ImageVector = Icons.Filled.Info
    val Warning: ImageVector = Icons.Filled.Warning
    val Error: ImageVector = Icons.Filled.ErrorOutline
    val Success: ImageVector = Icons.Filled.CheckCircle
}
