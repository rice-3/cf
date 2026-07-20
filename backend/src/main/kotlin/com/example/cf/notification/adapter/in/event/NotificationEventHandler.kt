package com.example.cf.notification.adapter.`in`.event

import com.example.cf.funding.application.SupportReferenceQuery
import com.example.cf.identity.application.UserReferenceQuery
import com.example.cf.notification.application.NotificationRequestPort
import com.example.cf.notification.domain.model.NotificationChannel
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.kernel.id.UserId
import com.example.cf.shared.outbox.OutboxMessage
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * ドメインイベントを購読して通知を登録する（基本設計 §4.6 の購読者マッピング）。
 * 実送信はBAT-005が行うため、ここではnotificationレコードの作成までとする。
 *
 * 宛先はイベントpayloadに含めず、UserIdからIdentityの公開契約経由で解決する
 * （outboxにメールアドレスを載せないため、§10.3 個人情報の最小化）。
 */
@Component
class NotificationEventHandler(
    private val notificationRequestPort: NotificationRequestPort,
    private val userReferenceQuery: UserReferenceQuery,
    private val supportReferenceQuery: SupportReferenceQuery,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun handle(message: OutboxMessage) {
        val templateId = TEMPLATES[message.eventType] ?: return

        val supporterUserId = resolveSupporterUserId(message)
        if (supporterUserId == null) {
            log.warn(
                "Notification skipped; recipient not resolved: eventType={} aggregateId={}",
                message.eventType, message.aggregateId,
            )
            return
        }
        val email = userReferenceQuery.findEmail(supporterUserId)

        notificationRequestPort.request(
            // 同一イベントを再配送されても通知は1件（§4.6 重複防止）
            businessKey = "${message.eventType}:${message.aggregateId}",
            channel = NotificationChannel.EMAIL,
            templateId = templateId,
            recipientUserId = UserId(supporterUserId),
            recipientAddress = email,
            // テンプレート変数は必要最小限に留める（§10.3）
            variables = mapOf(
                "eventType" to message.eventType,
                "aggregateId" to message.aggregateId,
            ),
        )
    }

    /** payloadのsupporterUserId、無ければsupportIdからFundingの公開契約で解決する。 */
    private fun resolveSupporterUserId(message: OutboxMessage): String? {
        (message.payload["supporterUserId"] as? String)
            ?.takeIf { ULID_PATTERN.matches(it) }
            ?.let { return it }

        val supportId = (message.payload["supportId"] as? Map<*, *>)?.get("value") as? String
            ?: message.payload["supportId"] as? String
            ?: return null
        if (!ULID_PATTERN.matches(supportId)) {
            return null
        }
        return supportReferenceQuery.findSupporterUserId(SupportId(supportId))
    }

    companion object {
        /**
         * 購読対象イベントとテンプレートID（基本設計 §4.6）。
         *
         * TODO(question): 起案者向け通知（ProjectApproved / ProjectReturned / ProjectPublished 等）は
         * 宛先解決に起案者UserIdが必要だが、現在のイベントpayloadに含まれていない。
         * イベントへownerUserIdを追加するか、Projectの公開契約に所有者参照を追加するかを要確認。
         */
        val TEMPLATES = mapOf(
            "SupportConfirmed" to "SUPPORT_CONFIRMED",
            "SupportPaymentFailed" to "SUPPORT_PAYMENT_FAILED",
            "RefundCompleted" to "REFUND_COMPLETED",
        )
    }
}
