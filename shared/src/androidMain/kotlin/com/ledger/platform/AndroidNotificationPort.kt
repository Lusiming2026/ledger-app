package com.ledger.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ledger.domain.port.NotificationSchedulerPort

/**
 * [NotificationSchedulerPort] 的 Android 实现（T21）。
 *
 * 权限说明（PRD §11.4「通知权限被拒绝时，App 内进度条兜底预警」）：
 *  - Android 13（API 33）起发通知需要运行时权限 `POST_NOTIFICATIONS`；
 *  - [hasPermission] 用于让 UI 决定是否显示「去开启通知」引导；
 *  - 未授权时 [postBudgetAlert] **静默跳过**，不抛异常——预算预警的兜底展示
 *    由首页/预算页的进度条高亮承担（`BudgetService.inAppWarningLevel`）。
 *
 * 幂等说明：本类**不做**任何「是否通知过」的判断，那是 `budget_notification`
 * 表的职责（唯一索引 `uq_notify_once`，PRD §7.3 / §11.4）。
 */
class AndroidNotificationPort(private val context: Context) : NotificationSchedulerPort {

    init {
        ensureChannel()
    }

    override fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Android 的权限请求必须由 Activity 发起（`ActivityResultLauncher`），
     * 端口拿不到 Activity，因此这里只能回报当前状态；
     * UI 侧用 `rememberLauncherForActivityResult(RequestPermission())` 真正发起请求，
     * 再在设置页提供「去系统设置开启」入口。
     */
    override fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(hasPermission())
    }

    override fun postBudgetAlert(
        budgetId: String,
        threshold: Int,
        periodKey: String,
        title: String,
        body: String,
    ) {
        if (!hasPermission()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            // 同一 (预算, 阈值, 周期) 复用同一个通知 ID：即使上层逻辑出错重复投递，
            // 系统层面也只会覆盖同一条通知，不会刷屏。
            NotificationManagerCompat.from(context)
                .notify(notificationId(budgetId, threshold, periodKey), notification)
        } catch (e: SecurityException) {
            // 权限在运行时被回收：静默降级到 App 内预警
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "预算提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "当月支出达到预算的 80% / 100% 时提醒" },
        )
    }

    private fun notificationId(budgetId: String, threshold: Int, periodKey: String): Int =
        "$budgetId|$threshold|$periodKey".hashCode()

    private companion object {
        const val CHANNEL_ID = "ledger_budget_alert"
    }
}
