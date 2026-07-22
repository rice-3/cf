package com.example.cf.shared.outbox

import com.example.cf.shared.observability.BatchMetrics
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Outbox配送設定（基本設計 §8.1 BAT-006、アルゴリズムは詳細設計 §9.1）。 */
@ConfigurationProperties(prefix = "cf.outbox")
data class OutboxProperties(
    val enabled: Boolean = true,
    /** 1トランザクションあたりの取得件数（§9.1: LIMIT 50）。 */
    val batchSize: Int = 50,
    /** 再試行上限（§9.2: 最大5回）。超過でERROR固定。 */
    val maxRetryCount: Int = 5,
)

/**
 * イベント配送先（§9.1: SQSまたはアプリ内Handler）。
 * localは内部Handler、dev以上はSQS Adapterへ差し替える。
 */
interface OutboxDispatcher {
    fun dispatch(event: OutboxEventJpaEntity)
}

/**
 * BAT-006 Outbox配送Worker（基本設計 §8.1。アルゴリズムは詳細設計 §9.1）。
 *
 * バッチIDは基本設計 §8.1 を正とする（詳細設計 §9 は同じ処理をBAT-003としている）。
 *
 * PENDING/ERRORのうち再試行時刻に達したイベントを `FOR UPDATE SKIP LOCKED` で取得し、
 * 配送する。失敗時は指数Backoffで再試行し、上限超過でERROR固定とする（§9.2）。
 */
@Component
class OutboxWorker(
    private val repository: OutboxEventJpaRepository,
    private val dispatcher: OutboxDispatcher,
    private val properties: OutboxProperties,
    private val clock: Clock,
    private val batchMetrics: BatchMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val BATCH_NAME = "BAT-006-outbox"
    }

    @Scheduled(fixedDelayString = "\${cf.outbox.fixed-delay-ms:5000}")
    fun run() {
        if (!properties.enabled) {
            return
        }
        runCatching { publishBatch() }
            .onSuccess { batchMetrics.recordSuccess(BATCH_NAME) }
            .onFailure {
                log.error("Outbox batch failed", it)
                batchMetrics.recordFailure(BATCH_NAME)
            }
    }

    /**
     * 1バッチ分を配送する。テストから直接呼べるようpublicにしている。
     * @return 処理件数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun publishBatch(): Int {
        val now = clock.instant()
        val events = repository.lockPendingBatch(now, properties.batchSize)
        events.forEach { event ->
            try {
                dispatcher.dispatch(event)
                event.publishStatus = "PUBLISHED"
                event.publishedAt = now
                event.nextRetryAt = null
            } catch (e: Exception) {
                applyRetry(event, now, e)
            }
            repository.save(event)
        }
        return events.size
    }

    private fun applyRetry(event: OutboxEventJpaEntity, now: Instant, cause: Exception) {
        event.retryCount += 1
        if (event.retryCount >= properties.maxRetryCount) {
            event.publishStatus = "ERROR"
            event.nextRetryAt = null
            // 上限超過は運用通知対象（§9.1）。メトリクスはoutbox_pending_countで監視する（§9.3）
            log.error(
                "Outbox event exhausted retries: eventId={} type={}",
                event.eventId,
                event.eventType,
                cause,
            )
        } else {
            event.publishStatus = "ERROR"
            event.nextRetryAt = now.plus(backoff(event.retryCount))
            log.warn(
                "Outbox dispatch failed, will retry: eventId={} retry={} cause={}",
                event.eventId,
                event.retryCount,
                cause.message,
            )
        }
    }

    /** 再試行間隔 1m, 5m, 15m, 1h, 6h（§9.2）。 */
    private fun backoff(retryCount: Int): Duration = when (retryCount) {
        1 -> Duration.ofMinutes(1)
        2 -> Duration.ofMinutes(5)
        3 -> Duration.ofMinutes(15)
        4 -> Duration.ofHours(1)
        else -> Duration.ofHours(6)
    }
}
