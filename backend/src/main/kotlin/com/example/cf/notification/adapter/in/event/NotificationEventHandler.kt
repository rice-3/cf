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
 * 宛先はイベントpayloadのUserId（起案者/支援者）からIdentityの公開契約経由でメールを解決する
 * （outboxにメールアドレスを載せないため、§10.3 個人情報の最小化）。
 * 宛先種別は購読イベントごとに [TemplateBinding.recipient] で決める（ADR-0002）。
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
        val binding = TEMPLATES[message.eventType] ?: return

        val recipientUserId = when (binding.recipient) {
            RecipientKind.OWNER -> resolveOwnerUserId(message)
            RecipientKind.SUPPORTER -> resolveSupporterUserId(message)
        }
        if (recipientUserId == null) {
            log.warn(
                "Notification skipped; recipient not resolved: eventType={} aggregateId={}",
                message.eventType,
                message.aggregateId,
            )
            return
        }
        val email = userReferenceQuery.findEmail(recipientUserId)

        notificationRequestPort.request(
            // 同一イベントを再配送されても通知は1件（§4.6 重複防止）
            businessKey = "${message.eventType}:${message.aggregateId}",
            channel = NotificationChannel.EMAIL,
            templateId = binding.templateId,
            recipientUserId = UserId(recipientUserId),
            recipientAddress = email,
            // テンプレート変数は必要最小限に留める（§10.3）。projectId等の非個人情報のみ。
            variables = mapOf(
                "eventType" to message.eventType,
                "resourceId" to message.aggregateId,
            ),
        )
    }

    /** 起案者向けイベント: payloadのownerUserIdから解決する（ADR-0002）。 */
    private fun resolveOwnerUserId(message: OutboxMessage): String? = readUlid(message.payload["ownerUserId"])

    /** 支援者向けイベント: payloadのsupporterUserId、無ければsupportIdからFundingの公開契約で解決する。 */
    private fun resolveSupporterUserId(message: OutboxMessage): String? {
        readUlid(message.payload["supporterUserId"])?.let { return it }

        val supportId = readUlid(message.payload["supportId"])
            ?: readUlid((message.payload["supportId"] as? Map<*, *>)?.get("value"))
            ?: return null
        return supportReferenceQuery.findSupporterUserId(SupportId(supportId))
    }

    /** Value Classはプレーン文字列でシリアライズされるが、念のため{value:...}形式も許容する。 */
    private fun readUlid(raw: Any?): String? {
        val value = when (raw) {
            is String -> raw
            is Map<*, *> -> raw["value"] as? String
            else -> null
        } ?: return null
        return value.takeIf { ULID_PATTERN.matches(it) }
    }

    private enum class RecipientKind { OWNER, SUPPORTER }

    private data class TemplateBinding(val templateId: String, val recipient: RecipientKind)

    companion object {
        /**
         * 購読対象イベントとテンプレート・宛先種別（基本設計 §4.6、ADR-0002）。
         * テンプレート本文は NotificationTemplateCatalog で定義する。
         */
        private val TEMPLATES = mapOf(
            // 支援者向け
            "SupportConfirmed" to TemplateBinding("SUPPORT_CONFIRMED", RecipientKind.SUPPORTER),
            "SupportPaymentFailed" to TemplateBinding("SUPPORT_PAYMENT_FAILED", RecipientKind.SUPPORTER),
            "RefundCompleted" to TemplateBinding("REFUND_COMPLETED", RecipientKind.SUPPORTER),
            // 起案者向け（ADR-0002でownerUserIdをイベントへ追加）
            "ProjectApproved" to TemplateBinding("PROJECT_APPROVED", RecipientKind.OWNER),
            "ProjectReturned" to TemplateBinding("PROJECT_RETURNED", RecipientKind.OWNER),
            "ProjectRejected" to TemplateBinding("PROJECT_REJECTED", RecipientKind.OWNER),
            "ProjectPublished" to TemplateBinding("PROJECT_PUBLISHED", RecipientKind.OWNER),
            "ProjectSucceeded" to TemplateBinding("PROJECT_SUCCEEDED", RecipientKind.OWNER),
            "ProjectFailed" to TemplateBinding("PROJECT_FAILED", RecipientKind.OWNER),
        )
    }
}
