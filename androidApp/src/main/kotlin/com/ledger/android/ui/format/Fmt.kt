package com.ledger.android.ui.format

import androidx.compose.ui.graphics.Color
import com.ledger.domain.model.Money
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/**
 * 展示层格式化工具（纯函数，无副作用）。
 *
 * 所有金额一律先经过 [Money.display]，**UI 层不允许自己写 `cents / 100.0`**：
 * PRD §11.6 明确「金额以 Int64『分』存储，全程禁用浮点」，
 * 一处 Double 就可能让 0.1 + 0.2 这类问题渗进账本。
 */
object Fmt {

    /** `¥1,234.56` / `-¥50.00` */
    fun money(cents: Long): String = Money(cents).display()

    /** 带正负号，用于收支流水行：`+¥1,200.00` / `-¥35.50` */
    fun signedMoney(cents: Long): String {
        val text = Money(cents).display()
        val sign = if (cents > 0L) "+" else ""
        return sign + text
    }

    /**
     * 输入框里的原始文本，不带 `¥` 与千分位：`1234.56`。
     * 用户重新编辑金额时不该看到格式化字符。
     */
    fun plainYuan(cents: Long): String {
        val negative = cents < 0L
        val abs = if (negative) -cents else cents
        val yuan = abs / 100L
        val fen = (abs % 100L).toInt()
        val body = if (fen == 0) yuan.toString() else "$yuan.${fen.toString().padStart(2, '0')}"
        return if (negative) "-$body" else body
    }

    /** `2026-10-09` */
    fun date(millis: Long): String {
        val d = local(millis)
        return "${d.year}-${pad(d.monthNumber)}-${pad(d.dayOfMonth)}"
    }

    /** `2026-10-09 14:30` */
    fun dateTime(millis: Long): String {
        val d = local(millis)
        return "${d.year}-${pad(d.monthNumber)}-${pad(d.dayOfMonth)} ${pad(d.hour)}:${pad(d.minute)}"
    }

    /** 流水分组头：`10月09日 周四` */
    fun dayHeader(millis: Long): String {
        val d = local(millis)
        return "${d.monthNumber}月${pad(d.dayOfMonth)}日 ${weekdayLabel(d.dayOfWeek.isoDayNumber)}"
    }

    /** 报表周期用的短日期：`10-09` */
    fun shortDate(millis: Long): String {
        val d = local(millis)
        return "${pad(d.monthNumber)}-${pad(d.dayOfMonth)}"
    }

    /** 文件大小：`1.2 KB` */
    fun fileSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024}.${(bytes % 1024) * 10 / 1024} KB"
        else -> "${bytes / (1024 * 1024)}.${(bytes % (1024 * 1024)) * 10 / (1024 * 1024)} MB"
    }

    /** 使用率展示：`78%` / `112%`（超支原样显示，便于一眼看出超了多少） */
    fun percent(ratio: Double): String = "${(ratio * 100.0).toInt()}%"

    private fun local(millis: Long) = Instant.fromEpochMilliseconds(millis)
        .toLocalDateTime(TimeZone.currentSystemDefault())

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private fun weekdayLabel(isoDayNumber: Int): String = when (isoDayNumber) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
}

/** 颜色字符串（`#RRGGBB` / `#AARRGGBB`）→ Compose Color。解析失败回退到 [fallback]，不抛异常。 */
fun parseHexColor(hex: String?, fallback: Color): Color {
    val raw = hex?.trim()?.removePrefix("#") ?: return fallback
    val normalized = when (raw.length) {
        6 -> "FF$raw"
        8 -> raw
        else -> return fallback
    }
    return runCatching { Color(normalized.toLong(16)) }.getOrDefault(fallback)
}
