package com.example.cf.shared.idempotency

import com.example.cf.shared.kernel.IdempotencyKey
import com.example.cf.shared.kernel.error.IdempotencyConflictException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

/**
 * idempotency_record（§8.21）による冪等記録Adapter。
 * PK(scope, actor_id, idempotency_key)の一意制約で同時実行を排除する。
 * 期限切れレコード（expires_at超過）は再実行を許可し、BAT相当の清掃対象とする。
 */
@Component
class IdempotencyPersistenceAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : IdempotencyPort {

    override fun begin(
        scope: String,
        actorId: String,
        key: IdempotencyKey,
        requestHash: String,
    ): IdempotencyOutcome {
        val now = clock.instant()
        val rows = jdbcTemplate.queryForList(
            """
            select status, request_hash, response_status, response_body, expires_at
              from idempotency_record
             where scope = ? and actor_id = ? and idempotency_key = ?
            """.trimIndent(),
            scope, actorId, key.value,
        )
        if (rows.isEmpty()) {
            insertProcessing(scope, actorId, key, requestHash)
            return IdempotencyOutcome.Proceed
        }

        val row = rows.first()
        val expiresAt = (row["expires_at"] as Timestamp).toInstant()
        if (!now.isBefore(expiresAt)) {
            resetToProcessing(scope, actorId, key, requestHash)
            return IdempotencyOutcome.Proceed
        }
        if (row["request_hash"] != requestHash) {
            throw IdempotencyConflictException(
                errorCode = "IDEMPOTENCY_KEY_CONFLICT",
                message = "Idempotency-Key is reused with a different request body",
            )
        }
        return when (row["status"]) {
            "COMPLETED" -> IdempotencyOutcome.Replay(
                responseStatus = row["response_status"] as Int,
                responseBody = parseBody(row["response_body"]),
            )
            "FAILED" -> {
                resetToProcessing(scope, actorId, key, requestHash)
                IdempotencyOutcome.Proceed
            }
            else -> throw IdempotencyConflictException(
                message = "The same request is being processed",
            )
        }
    }

    override fun complete(
        scope: String,
        actorId: String,
        key: IdempotencyKey,
        responseStatus: Int,
        responseBody: Map<String, Any?>,
    ) {
        jdbcTemplate.update(
            """
            update idempotency_record
               set status = 'COMPLETED', response_status = ?, response_body = ?::jsonb
             where scope = ? and actor_id = ? and idempotency_key = ?
            """.trimIndent(),
            responseStatus, objectMapper.writeValueAsString(responseBody),
            scope, actorId, key.value,
        )
    }

    private fun insertProcessing(scope: String, actorId: String, key: IdempotencyKey, requestHash: String) {
        val now = clock.instant()
        try {
            jdbcTemplate.update(
                """
                insert into idempotency_record
                    (scope, actor_id, idempotency_key, request_hash, status, expires_at, created_at)
                values (?, ?, ?, ?, 'PROCESSING', ?, ?)
                """.trimIndent(),
                scope, actorId, key.value, requestHash,
                Timestamp.from(now.plus(RETENTION)), Timestamp.from(now),
            )
        } catch (e: DuplicateKeyException) {
            // 同時実行の後着（先行コミット後に一意制約違反となる）
            throw IdempotencyConflictException(message = "The same request is being processed")
        }
    }

    private fun resetToProcessing(scope: String, actorId: String, key: IdempotencyKey, requestHash: String) {
        jdbcTemplate.update(
            """
            update idempotency_record
               set status = 'PROCESSING', request_hash = ?, response_status = null,
                   response_body = null, expires_at = ?, created_at = ?
             where scope = ? and actor_id = ? and idempotency_key = ?
            """.trimIndent(),
            requestHash,
            Timestamp.from(clock.instant().plus(RETENTION)), Timestamp.from(clock.instant()),
            scope, actorId, key.value,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBody(raw: Any?): Map<String, Any?> = when (raw) {
        null -> emptyMap()
        else -> objectMapper.readValue(raw.toString(), Map::class.java) as Map<String, Any?>
    }

    companion object {
        /** 冪等記録の保持期間（§7.7 データ保持の範囲内で24時間とする）。 */
        private val RETENTION: Duration = Duration.ofHours(24)
    }
}
