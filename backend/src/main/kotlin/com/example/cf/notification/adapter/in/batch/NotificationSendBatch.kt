package com.example.cf.notification.adapter.`in`.batch

import com.example.cf.notification.application.NotificationTransactionSteps
import com.example.cf.notification.application.SendNotificationUseCase
import com.example.cf.shared.batch.BatchProperties
import com.example.cf.shared.batch.batchAuditContext
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * BAT-005 通知送信（基本設計 §8.1）。
 * PENDINGおよび再試行時刻に達したRETRY_WAITの通知をSESへ送る。
 */
@Component
class NotificationSendBatch(
    private val steps: NotificationTransactionSteps,
    private val sendNotification: SendNotificationUseCase,
    private val properties: BatchProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${cf.batch.notification-interval-ms:60000}")
    @SchedulerLock(name = "BAT-005-notification", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    fun send() {
        if (!properties.enabled) return
        runCatching { runBatch() }
            .onFailure { log.error("BAT-005 notification batch failed", it) }
    }

    /** 対象取得と送信を分離し、外部送信中にトランザクションを保持しない。 */
    fun runBatch(): Int {
        val audit = batchAuditContext()
        val targets = steps.lockTargets(properties.workerBatchSize)
        targets.forEach { notificationId -> sendNotification.execute(notificationId, audit) }
        if (targets.isNotEmpty()) {
            log.info("BAT-005 processed {} notifications", targets.size)
        }
        return targets.size
    }
}
