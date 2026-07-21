package com.example.cf.shared.idempotency

import com.example.cf.shared.batch.BatchProperties
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * BAT-010 冪等記録削除（日次）。失効済み idempotency_record を物理削除する。
 *
 * 1回の起動で最大 [BatchProperties.idempotencyCleanupBatchSize] 件を削除し、
 * 上限に達した場合は次回起動で継続する（大量削除時のロック保持を避ける）。
 */
@Component
class IdempotencyCleanupBatch(
    private val useCase: IdempotencyCleanupUseCase,
    private val properties: BatchProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${cf.batch.idempotency-cleanup-cron:0 15 3 * * *}")
    @SchedulerLock(name = "BAT-010-idempotency-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun cleanup() {
        if (!properties.enabled) return
        runCatching {
            val deleted = useCase.execute(properties.idempotencyCleanupBatchSize)
            if (deleted > 0) {
                log.info("BAT-010 idempotency cleanup deleted {} expired records", deleted)
            }
        }.onFailure { log.error("BAT-010 idempotency cleanup batch failed", it) }
    }
}
