package com.example.cf.payment.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.funding.application.SupportPaymentResultPort
import com.example.cf.payment.domain.repository.PaymentRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.outbox.OutboxAppendPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val RESOURCE_TYPE = "Payment"

/** Webhook処理結果（§5.4-9: 正常・重複ともに204）。 */
enum class WebhookProcessResult {
    PROCESSED,
    DUPLICATE,
}

/** 受信履歴のINSERT結果（§5.4-3/4）。 */
enum class WebhookReceiveOutcome {
    /** 新規受信。以降の状態遷移を行う。 */
    NEW,

    /** 同一event_id・同一payloadの再送。処理済みとして正常終了する。 */
    DUPLICATE,

    /** 同一event_idだがpayloadが異なる。改ざんまたはProvider異常（§5.4-4）。 */
    PAYLOAD_MISMATCH,
}

/** 受信履歴Port（payment_webhook_event、§8.13）。 */
interface WebhookEventRecordPort {

    /**
     * 受信履歴をINSERTする（§5.4-3）。
     * 例外は投げない。異常は [WebhookReceiveOutcome] で返し、呼出し側が同一トランザクション内で
     * ERROR記録できるようにする（例外送出だとERROR記録ごとロールバックされるため）。
     */
    fun recordReceived(event: VerifiedWebhookEvent, provider: String): WebhookReceiveOutcome

    fun markProcessed(eventId: String)

    fun markError(eventId: String, errorCode: String)
}

/**
 * UC-PY-001 決済Webhook受信（API-PY-001、詳細設計 §5.4）。
 */
interface HandlePaymentWebhookUseCase {
    fun execute(event: VerifiedWebhookEvent, audit: AuditContext): WebhookProcessResult
}

@Service
class HandlePaymentWebhookService(
    private val paymentRepository: PaymentRepository,
    private val webhookRecordPort: WebhookEventRecordPort,
    private val supportResultPort: SupportPaymentResultPort,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : HandlePaymentWebhookUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun execute(event: VerifiedWebhookEvent, audit: AuditContext): WebhookProcessResult {
        // 3〜4. 受信履歴INSERT。重複は正常終了、payload相違はERROR記録して終了する。
        when (webhookRecordPort.recordReceived(event, PROVIDER)) {
            WebhookReceiveOutcome.DUPLICATE -> return WebhookProcessResult.DUPLICATE
            WebhookReceiveOutcome.PAYLOAD_MISMATCH -> {
                webhookRecordPort.markError(event.eventId, "WEBHOOK_PAYLOAD_MISMATCH")
                auditPort.record(audit, "PAYMENT_WEBHOOK", RESOURCE_TYPE, event.providerPaymentId, "FAILURE")
                log.warn("Webhook payload mismatch for eventId={}", event.eventId)
                return WebhookProcessResult.DUPLICATE
            }
            WebhookReceiveOutcome.NEW -> Unit
        }

        // 5. provider_payment_idでPaymentをロック付き取得
        val payment = paymentRepository.findByProviderPaymentId(PROVIDER, event.providerPaymentId)
            ?: run {
                webhookRecordPort.markError(event.eventId, "PAYMENT_NOT_FOUND")
                log.warn("Webhook references unknown payment: eventId={}", event.eventId)
                return WebhookProcessResult.PROCESSED
            }
        val locked = paymentRepository.findByIdForUpdate(payment.id)
            ?: throw InvalidStateException("PAYMENT_NOT_FOUND", "Payment ${payment.id.value} disappeared")

        val now = clock.instant()

        // 6〜7. イベント種別と現在状態の組合せを検証して遷移させる
        try {
            when (event.eventType) {
                "payment.succeeded" -> {
                    val paymentEvent = locked.succeed(event.providerPaymentId, now)
                    paymentRepository.save(locked)
                    supportResultPort.confirmBySupportId(locked.supportId, audit)
                    outbox.append(paymentEvent, audit.correlationId)
                    auditPort.record(audit, "PAYMENT_SUCCEEDED", RESOURCE_TYPE, locked.id.value, "SUCCESS")
                }
                "payment.failed" -> {
                    val failureCode = event.payload["failureCode"] as? String
                    val paymentEvent = locked.fail(failureCode, now)
                    paymentRepository.save(locked)
                    supportResultPort.failBySupportId(locked.supportId, audit)
                    outbox.append(paymentEvent, audit.correlationId)
                    auditPort.record(audit, "PAYMENT_FAILED", RESOURCE_TYPE, locked.id.value, "SUCCESS")
                }
                else -> {
                    webhookRecordPort.markError(event.eventId, "UNSUPPORTED_EVENT_TYPE")
                    log.warn("Unsupported webhook event type: {}", event.eventType)
                    return WebhookProcessResult.PROCESSED
                }
            }
        } catch (e: InvalidStateException) {
            // 状態不整合は再送しても解決しないためERROR記録に留め、204を返す（§5.4-9）
            webhookRecordPort.markError(event.eventId, e.errorCode)
            auditPort.record(audit, "PAYMENT_WEBHOOK", RESOURCE_TYPE, locked.id.value, "FAILURE")
            log.warn("Webhook state conflict: paymentId={} code={}", locked.id.value, e.errorCode)
            return WebhookProcessResult.PROCESSED
        }

        // 8. processed_at / PROCESSED を更新
        webhookRecordPort.markProcessed(event.eventId)
        return WebhookProcessResult.PROCESSED
    }

    companion object {
        const val PROVIDER = "SANDBOX"
    }
}
