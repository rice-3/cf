package com.example.cf.shared.kernel.time

import java.time.Duration
import java.time.Instant

/**
 * 開始・終了期間Value Object（詳細設計 §3.1）。
 * 開始 < 終了。日時はUTC [Instant] で保持する（§1.3）。
 */
data class DateRange(
    val start: Instant,
    val end: Instant,
) {
    init {
        require(start.isBefore(end)) { "DateRange start must be before end: start=$start end=$end" }
    }

    val duration: Duration get() = Duration.between(start, end)

    /** now が期間内（開始以上・終了未満）か。 */
    fun contains(now: Instant): Boolean = !now.isBefore(start) && now.isBefore(end)

    fun isStarted(now: Instant): Boolean = !now.isBefore(start)

    fun isEnded(now: Instant): Boolean = !now.isBefore(end)

    fun requireMaxDays(maxDays: Long) {
        require(duration <= Duration.ofDays(maxDays)) {
            "DateRange must be within $maxDays days: $duration"
        }
    }
}
