package com.example.cf.file.adapter.`in`.batch

import com.example.cf.file.application.FileCleanupUseCase
import com.example.cf.shared.batch.BatchProperties
import com.example.cf.shared.batch.batchAuditContext
import com.example.cf.shared.observability.BatchMetrics
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * BAT-008 ファイル清掃（基本設計 §8.1、日次）。
 * 未完了かつ失効したアップロードのS3実体を削除し、FileObjectをDELETEDにする。
 */
@Component
class FileCleanupBatch(
    private val fileCleanup: FileCleanupUseCase,
    private val properties: BatchProperties,
    private val batchMetrics: BatchMetrics,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${cf.batch.file-cleanup-cron:0 30 3 * * *}")
    @SchedulerLock(name = "BAT-008-file-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun cleanup() {
        if (!properties.enabled) return
        runCatching { fileCleanup.execute(properties.fileCleanupBatchSize, batchAuditContext()) }
            .onSuccess { batchMetrics.recordSuccess("BAT-008-file-cleanup") }
            .onFailure {
                log.error("BAT-008 file cleanup batch failed", it)
                batchMetrics.recordFailure("BAT-008-file-cleanup")
            }
    }
}
