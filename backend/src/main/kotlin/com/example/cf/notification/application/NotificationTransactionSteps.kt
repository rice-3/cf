package com.example.cf.notification.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.notification.domain.model.NotificationChannel
import com.example.cf.notification.domain.model.NotificationStatus
import com.example.cf.notification.domain.repository.NotificationRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.id.NotificationId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 送信処理のトランザクション境界（BAT-005）。
 *
 * `REQUIRES_NEW` は自己呼出しではプロキシを経由せず有効にならないため、
 * 呼出し元のUseCaseとは別Beanへ切り出している。
 */
@Service
class NotificationTransactionSteps(
    private val repository: NotificationRepository,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 送信に必要な情報。SES呼出し中はトランザクションを保持しない。 */
    data class SendingTarget(
        val channel: NotificationChannel,
        val templateId: String,
        val recipientAddress: String?,
        val variables: Map<String, Any?>,
        val attemptNo: Int,
    )

    // SELECT ... FOR UPDATE は読み取り専用トランザクションでは実行できないため readOnly にしない
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun lockTargets(limit: Int): List<NotificationId> =
        repository.lockSendableBatch(clock.instant(), limit).map { it.id }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun startSending(notificationId: NotificationId): SendingTarget? {
        val notification = repository.findByIdForUpdate(notificationId) ?: return null
        notification.startSending(clock.instant())
        repository.save(notification)
        return SendingTarget(
            channel = notification.channel,
            templateId = notification.templateId,
            recipientAddress = notification.recipientAddress,
            variables = notification.variables,
            attemptNo = notification.retryCount + 1,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finishSent(notificationId: NotificationId, attemptNo: Int, providerMessageId: String?) {
        val notification = repository.findByIdForUpdate(notificationId) ?: return
        val now = clock.instant()
        notification.markSent(now)
        repository.save(notification)
        repository.recordDelivery(notificationId, attemptNo, providerMessageId, "SUCCESS", null, now)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finishFailed(
        notificationId: NotificationId,
        attemptNo: Int,
        errorCode: String,
        permanent: Boolean,
        audit: AuditContext,
    ) {
        val notification = repository.findByIdForUpdate(notificationId) ?: return
        val now = clock.instant()
        notification.fail(permanent, now)
        repository.save(notification)
        repository.recordDelivery(notificationId, attemptNo, null, "FAILURE", errorCode, now)

        if (notification.status == NotificationStatus.FAILED) {
            auditPort.record(audit, "NOTIFICATION_FAILED", "Notification", notificationId.value, "FAILURE")
            log.error(
                "Notification permanently failed: notificationId={} template={} errorCode={}",
                notificationId.value, notification.templateId, errorCode,
            )
        }
    }
}
