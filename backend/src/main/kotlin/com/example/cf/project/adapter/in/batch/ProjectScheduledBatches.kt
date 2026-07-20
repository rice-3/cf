package com.example.cf.project.adapter.`in`.batch

import com.example.cf.project.application.usecase.CloseFundingUseCase
import com.example.cf.project.application.usecase.PublishApprovedProjectsUseCase
import com.example.cf.shared.batch.BatchProperties
import com.example.cf.shared.batch.batchAuditContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Projectコンテキストの定期バッチ起動（BAT-001 / BAT-002、基本設計 §8.1）。
 *
 * 起動アダプタは制御のみを担い、業務処理はUseCaseへ委譲する。
 * 対象行は `FOR UPDATE SKIP LOCKED` で取得するため、多重起動しても二重処理にならない（§8.3）。
 */
@Component
class ProjectScheduledBatches(
    private val publishApprovedProjects: PublishApprovedProjectsUseCase,
    private val closeFunding: CloseFundingUseCase,
    private val properties: BatchProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** BAT-001 公開開始処理（1分ごと）。 */
    @Scheduled(fixedDelayString = "\${cf.batch.publish-interval-ms:60000}")
    fun publishApproved() {
        if (!properties.enabled) return
        runCatching { publishApprovedProjects.execute(properties.projectBatchSize, batchAuditContext()) }
            .onFailure { log.error("BAT-001 publish batch failed", it) }
    }

    /** BAT-002 募集終了処理（1分ごと）。 */
    @Scheduled(fixedDelayString = "\${cf.batch.close-funding-interval-ms:60000}")
    fun closeFundingPeriod() {
        if (!properties.enabled) return
        runCatching { closeFunding.execute(properties.projectBatchSize, batchAuditContext()) }
            .onFailure { log.error("BAT-002 funding close batch failed", it) }
    }
}
