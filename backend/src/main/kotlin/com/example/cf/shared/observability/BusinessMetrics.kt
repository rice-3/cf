package com.example.cf.shared.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.function.Supplier

/**
 * ビジネス滞留・失敗の状態メトリクス（詳細設計 §9.3、基本設計 §12.6）。
 *
 * Outbox未配送・通知失敗・返金再試行など「溜まると危険な状態」をゲージで公開する。
 * スクレイプ時点で軽量なCOUNTクエリを実行する読み取り専用メトリクスのため、
 * コンテキスト境界を跨がず [JdbcTemplate] で直接集計する（監視の横断的関心事）。
 *
 * 公開ゲージ:
 * - `cf_outbox_pending_count` / `cf_outbox_oldest_age_seconds`
 * - `cf_notification_pending_count` / `cf_notification_failed_count`
 * - `cf_refund_retry_wait_count` / `cf_refund_failed_count`
 *
 * 通知の送信結果レート（`cf_notification_delivery_total{result}`）は送信時に
 * [com.example.cf.notification.adapter.out.persistence.NotificationPersistenceAdapter] が加算する。
 */
@Component
class BusinessMetrics(
    private val meterRegistry: MeterRegistry,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun bindMetrics() {
        gauge(
            "cf_outbox_pending_count",
            "未配送のOutboxイベント数（PENDING/ERROR）。増加は配送停止・連続失敗の兆候。",
        ) {
            count("select count(*) from outbox_event where publish_status in ('PENDING', 'ERROR')")
        }
        gauge(
            "cf_outbox_oldest_age_seconds",
            "最古の未配送Outboxイベントの経過秒。滞留時間のSLO監視に用いる。",
            baseUnit = "seconds",
        ) {
            oldestAgeSeconds("select min(occurred_at) from outbox_event where publish_status in ('PENDING', 'ERROR')")
        }
        gauge(
            "cf_notification_pending_count",
            "送信待ち通知数（PENDING/RETRY_WAIT）。",
        ) {
            count("select count(*) from notification where status in ('PENDING', 'RETRY_WAIT')")
        }
        gauge(
            "cf_notification_failed_count",
            "送信失敗（上限超過）で確定した通知数（FAILED）。",
        ) {
            count("select count(*) from notification where status = 'FAILED'")
        }
        gauge(
            "cf_refund_retry_wait_count",
            "再試行待ちの返金数（RETRY_WAIT）。",
        ) {
            count("select count(*) from refund where status = 'RETRY_WAIT'")
        }
        gauge(
            "cf_refund_failed_count",
            "再試行上限を超えFAILEDで滞留する返金数。運用者の手動対応が必要。",
        ) {
            count("select count(*) from refund where status = 'FAILED'")
        }
    }

    private fun gauge(name: String, description: String, baseUnit: String? = null, value: () -> Double) {
        Gauge.builder(name, Supplier { safe(name, value) })
            .description(description)
            .apply { if (baseUnit != null) baseUnit(baseUnit) }
            .register(meterRegistry)
    }

    /** スクレイプ時のDB例外（起動直後/シャットダウン中など）でメトリクス収集全体を落とさない。 */
    private fun safe(name: String, value: () -> Double): Double = runCatching { value() }
        .getOrElse {
            log.debug("metric {} collection failed", name, it)
            Double.NaN
        }

    private fun count(sql: String): Double = (jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0L).toDouble()

    private fun oldestAgeSeconds(sql: String): Double {
        val oldest = jdbcTemplate.queryForObject(sql, java.sql.Timestamp::class.java) ?: return 0.0
        return (clock.instant().toEpochMilli() - oldest.time) / 1000.0
    }
}
