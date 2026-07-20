package com.example.cf.notification.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.notification.domain.model.Notification
import com.example.cf.notification.domain.model.NotificationChannel
import com.example.cf.notification.domain.repository.NotificationRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.id.NotificationId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.id.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Notificationコンテキスト公開契約（基本設計 §4.1）。
 * 他コンテキストは通知テーブルを直接操作せず、本Port経由で通知を要求する。
 */
interface NotificationRequestPort {

    /**
     * 通知を登録する（実送信はBAT-005）。
     * businessKey＋channelが既存の場合は何もしない（§4.6 重複防止）。
     */
    fun request(
        businessKey: String,
        channel: NotificationChannel,
        templateId: String,
        recipientUserId: UserId?,
        recipientAddress: String?,
        variables: Map<String, Any?>,
    ): NotificationId?
}

@Service
class NotificationRequestService(
    private val repository: NotificationRepository,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : NotificationRequestPort {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun request(
        businessKey: String,
        channel: NotificationChannel,
        templateId: String,
        recipientUserId: UserId?,
        recipientAddress: String?,
        variables: Map<String, Any?>,
    ): NotificationId? {
        if (repository.existsByBusinessKey(businessKey, channel)) {
            return null
        }
        val notification = Notification.create(
            id = NotificationId.newId(idGenerator),
            businessKey = businessKey,
            channel = channel,
            templateId = templateId,
            recipientUserId = recipientUserId,
            recipientAddress = recipientAddress,
            variables = variables,
            now = clock.instant(),
        )
        repository.save(notification)
        log.debug("Notification queued: template={} channel={}", templateId, channel)
        return notification.id
    }
}

/**
 * 通知送信（BAT-005）。1件ずつ送信し、結果をnotification_deliveryへ記録する。
 * 外部送信（SES）はトランザクション外で行い、前後を短いトランザクションに分ける。
 */
interface SendNotificationUseCase {
    fun execute(notificationId: NotificationId, audit: AuditContext)
}

@Service
class SendNotificationService(
    private val steps: NotificationTransactionSteps,
    private val sender: NotificationSenderPort,
) : SendNotificationUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(notificationId: NotificationId, audit: AuditContext) {
        val target = steps.startSending(notificationId) ?: return

        val result = try {
            sender.send(target.channel, target.templateId, target.recipientAddress, target.variables)
        } catch (e: Exception) {
            log.warn("Notification sender threw: notificationId={}", notificationId.value, e)
            SendResult.Failed("SENDER_ERROR", permanent = false)
        }

        when (result) {
            is SendResult.Sent -> steps.finishSent(notificationId, target.attemptNo, result.providerMessageId)
            is SendResult.Failed -> steps.finishFailed(
                notificationId, target.attemptNo, result.errorCode, result.permanent, audit,
            )
        }
    }
}
