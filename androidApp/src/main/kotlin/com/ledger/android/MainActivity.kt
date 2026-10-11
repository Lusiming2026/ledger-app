package com.ledger.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ledger.android.ui.LedgerApp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 唯一的 Activity（Compose 单 Activity 架构）。
 *
 * 两件事：
 *  1. `enableEdgeToEdge()` —— targetSdk 35 起系统强制 edge-to-edge，显式调用让
 *     行为在各版本上一致；内边距由 Compose 的 Scaffold / TopAppBar / NavigationBar 处理；
 *  2. 处理 T27 桌面快捷方式「记一笔」：冷启动读 `intent`，热启动（singleTop）走
 *     [onNewIntent]，统一用 [recordRequest] 通知 Compose 层导航。
 */
class MainActivity : ComponentActivity() {

    private val recordRequest = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        recordRequest.value = isRecordIntent(intent)

        setContent {
            LedgerApp(
                recordRequest = recordRequest,
                onRecordRequestHandled = { recordRequest.value = false },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isRecordIntent(intent)) {
            recordRequest.value = true
        }
    }

    private fun isRecordIntent(intent: Intent?): Boolean =
        intent?.action == LedgerApplication.ACTION_RECORD
}
