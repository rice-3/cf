package com.example.cf.shared.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * バッチ稼働メトリクス（詳細設計 §9.3）。
 *
 * 各定期バッチ（BAT-001/002/004/005/006/007/008/010）が実行結果をここへ記録する:
 * - `cf_batch_last_success_age_seconds{batch}` — 最終成功からの経過秒。
 *   閾値超過でバッチ停止・滞留を検知する（例: 1分周期のバッチが5分以上更新されない）。
 * - `cf_batch_runs_total{batch,outcome}` — 成功/失敗回数のカウンタ。失敗率アラートに用いる。
 *
 * バッチが一度も成功していない場合、経過秒ゲージは NaN を返す（集計側で未実行として扱う）。
 */
@Component
class BatchMetrics(
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {

    private val lastSuccessAt = ConcurrentHashMap<String, Instant>()
    private val registeredAgeGauges = ConcurrentHashMap.newKeySet<String>()

    /** バッチ成功を記録し、最終成功時刻を更新する。 */
    fun recordSuccess(batchName: String) {
        lastSuccessAt[batchName] = clock.instant()
        ensureAgeGauge(batchName)
        meterRegistry.counter("cf_batch_runs_total", "batch", batchName, "outcome", "success").increment()
    }

    /** バッチ失敗を記録する（最終成功時刻は更新しないため経過秒は増え続ける）。 */
    fun recordFailure(batchName: String) {
        ensureAgeGauge(batchName)
        meterRegistry.counter("cf_batch_runs_total", "batch", batchName, "outcome", "failure").increment()
    }

    private fun ensureAgeGauge(batchName: String) {
        if (registeredAgeGauges.add(batchName)) {
            Gauge.builder("cf_batch_last_success_age_seconds") { ageSeconds(batchName) }
                .description("最終成功からの経過秒。閾値超過で滞留・停止を検知する（詳細設計 §9.3）")
                .baseUnit("seconds")
                .tag("batch", batchName)
                .register(meterRegistry)
        }
    }

    private fun ageSeconds(batchName: String): Double {
        val last = lastSuccessAt[batchName] ?: return Double.NaN
        return (clock.instant().toEpochMilli() - last.toEpochMilli()) / 1000.0
    }
}
