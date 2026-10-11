package com.ledger.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.ledger.domain.port.PreferenceKeys

/** P14 外观模式三态（PRD §4.2 P14：跟随系统 / 浅色 / 深色）。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** 落到 [PreferenceKeys.THEME_MODE] 的字符串 */
    val prefValue: String
        get() = when (this) {
            SYSTEM -> PreferenceKeys.THEME_SYSTEM
            LIGHT -> PreferenceKeys.THEME_LIGHT
            DARK -> PreferenceKeys.THEME_DARK
        }

    companion object {
        fun fromPref(value: String?): ThemeMode = when (value) {
            PreferenceKeys.THEME_LIGHT -> LIGHT
            PreferenceKeys.THEME_DARK -> DARK
            else -> SYSTEM
        }
    }
}

/**
 * 语义色（与主题明暗无关的业务色）。
 *
 * 记账场景的配色遵循中文用户习惯：**支出用暖色、收入用绿色**、转账用中性蓝。
 * 注意这与股票涨跌的红绿约定方向不同——这里表达的是「流出 / 流入」，
 * 所有报表与列表必须统一取用这三个常量，避免各页各自 hardcode。
 */
object LedgerSemanticColors {
    val Expense = Color(0xFFD64B4B)
    val Income = Color(0xFF2F9E68)
    val Transfer = Color(0xFF3A7BD5)

    /** 预算进度条的三档预警色（PRD §7.3 / §11.4 的 App 内兜底预警） */
    val BudgetNormal = Color(0xFF2E7D6F)
    val BudgetWarning = Color(0xFFE08A2B)
    val BudgetExceeded = Color(0xFFD64B4B)

    fun forWarningLevel(level: Int): Color = when {
        level >= 2 -> BudgetExceeded
        level == 1 -> BudgetWarning
        else -> BudgetNormal
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6E60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFAAEFE0),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF456179),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBF7F2),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBF7F2),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainer = Color(0xFFF0EDE8),
    surfaceContainerHigh = Color(0xFFEAE7E2),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED3C5),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0B5145),
    onPrimaryContainer = Color(0xFFAAEFE0),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF324B45),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFADCAE5),
    onTertiary = Color(0xFF143349),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainer = Color(0xFF1B1F1E),
    surfaceContainerHigh = Color(0xFF252A28),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * 版式。
 *
 * 刻意**不给任何 TextStyle 设固定行高以外的高度约束**，也不设 `maxLines`
 * （个别标题除外）：PRD §11.7 要求「系统字体缩放 ≥ 1.3 倍不破版、关键内容不裁切」，
 * Compose 默认会按 `fontScale` 放大，而写死高度或 `maxLines = 1` 才是破版的根因。
 */
private val LedgerTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
)

private val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 主题壳。不启用 Material You 动态取色：品牌色是产品识别的一部分，
 * 且动态色会让「支出红 / 收入绿」的语义色与容器色撞色，反而不易读。
 *
 * 状态栏图标明暗随主题切换（`isAppearanceLightStatusBars`），
 * 否则深色模式下状态栏会出现「白底白字」。
 */
@Composable
fun LedgerTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = LedgerTypography,
        shapes = LedgerShapes,
        content = content,
    )
}
