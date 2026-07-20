package com.example.cf.payment.adapter.out.persistence

import com.example.cf.payment.application.VerifiedWebhookEvent
import com.example.cf.payment.application.WebhookEventRecordPort
import com.example.cf.payment.application.WebhookReceiveOutcome
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock

/**
 * payment_webhook_event（§8.13）への受信履歴Adapter。
 * 外部event_idの主キー一意制約で重複受信を排除する（§5.4-3）。
 */
@Component
class WebhookEventPersistenceAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : WebhookEventRecordPort {

    override fun recordReceived(event: VerifiedWebhookEvent, provider: String): WebhookReceiveOutcome {
        val existing = jdbcTemplate.queryForList(
            "select payload_hash from payment_webhook_event where webhook_event_id = ?",
            event.eventId,
        )
        if (existing.isNotEmpty()) {
            return if (existing.first()["payload_hash"] != event.payloadHash) {
                WebhookReceiveOutcome.PAYLOAD_MISMATCH
            } else {
                WebhookReceiveOutcome.DUPLICATE
            }
        }

        return try {
            jdbcTemplate.update(
                """
                insert into payment_webhook_event
                    (webhook_event_id, provider, event_type, payload_hash, payload,
                     received_at, process_status)
                values (?, ?, ?, ?, ?::jsonb, ?, 'RECEIVED')
                """.trimIndent(),
                event.eventId, provider, event.eventType, event.payloadHash,
                objectMapper.writeValueAsString(event.payload),
                Timestamp.from(clock.instant()),
            )
            WebhookReceiveOutcome.NEW
        } catch (e: DuplicateKeyException) {
            // 同時受信の後着は処理済み扱い（§5.5 Webhook: 重複は正常終了）
            WebhookReceiveOutcome.DUPLICATE
        }
    }

    override fun markProcessed(eventId: String) {
        jdbcTemplate.update(
            """
            update payment_webhook_event
               set process_status = 'PROCESSED', processed_at = ?
             where webhook_event_id = ?
            """.trimIndent(),
            Timestamp.from(clock.instant()), eventId,
        )
    }

    override fun markError(eventId: String, errorCode: String) {
        jdbcTemplate.update(
            """
            update payment_webhook_event
               set process_status = 'ERROR', last_error_code = ?, retry_count = retry_count + 1
             where webhook_event_id = ?
            """.trimIndent(),
            errorCode, eventId,
        )
    }
}
