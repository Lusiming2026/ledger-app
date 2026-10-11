package com.ledger.data.platform

import com.ledger.domain.model.YearMonth
import com.ledger.domain.port.ClockPort
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * [ClockPort] 的生产实现。
 *
 * 放在 `commonMain` 而非各平台源集：系统时钟的语义在两端完全一致，
 * 由 `kotlinx-datetime` 统一提供，重复写两份 java.time / Foundation 版本
 * 只会带来行为漂移（尤其是时区与夏令时）。平台的「个性化」能力（可用时区库等）
 * 由 kotlinx-datetime 的 expect/actual 内部处理。
 */
class SystemClock(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ClockPort {

    override fun nowUtcMillis(): Long = Clock.System.now().toEpochMilliseconds()

    override fun currentYearMonth(): YearMonth =
        YearMonth.ofUtcMillis(nowUtcMillis(), timeZone)
}
