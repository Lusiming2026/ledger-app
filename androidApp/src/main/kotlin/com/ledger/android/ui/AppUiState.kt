package com.ledger.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.ledger.android.di.inject
import com.ledger.android.ui.theme.ThemeMode
import com.ledger.domain.port.PreferenceKeys
import com.ledger.domain.port.PreferencePort

/**
 * 跨页面的界面级状态（目前只有外观模式）。
 *
 * 为什么不放 ViewModel：外观模式要在**主题壳之上**读取（否则会先按默认主题渲染一帧再跳变），
 * 而 `viewModel()` 只能在 `NavHost` 内部使用。因此用一个 App 级持有者 + CompositionLocal。
 *
 * 持久化走 [PreferencePort]，[updateThemeMode] 会**立即**写盘，
 * 这样 P14 点一下就是即时生效（PRD §4.2 P14「即时生效」/ §11.7）。
 */
class AppUiState(private val preference: PreferencePort) {

    var themeMode: ThemeMode by mutableStateOf(
        ThemeMode.fromPref(preference.getString(PreferenceKeys.THEME_MODE)),
    )
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        preference.putString(PreferenceKeys.THEME_MODE, mode.prefValue)
    }
}

val LocalAppUiState = staticCompositionLocalOf<AppUiState> {
    error("LocalAppUiState 未提供：请在 LedgerApp 的 CompositionLocalProvider 内使用")
}

@Composable
fun rememberAppUiState(): AppUiState = remember { AppUiState(inject<PreferencePort>()) }
