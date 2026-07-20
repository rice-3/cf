package com.example.cf.payment.adapter.`in`.event

import com.example.cf.payment.application.CreateRefundUseCase
import com.example.cf.shared.batch.batchAuditContext
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.outbox.OutboxMessage
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * BAT-003 返金対象作成（基本設計 §8.1、起動=イベント）。
 *
 * `ProjectFailed` のみを購読する。成立・不成立が別イベントに分かれているため、
 * payloadを解釈して返金要否を判定する必要はない。
 * 重複受信しても既存の有効な返金がある支援は読み飛ばされるため、二重作成にならない。
 */
@Component
class ProjectFailedHandler(
    private val createRefund: CreateRefundUseCase,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun handle(message: OutboxMessage) {
        if (message.eventType != "ProjectFailed") {
            return
        }
        if (!ULID_PATTERN.matches(message.aggregateId)) {
            log.error("ProjectFailed has an invalid aggregateId: {}", message.aggregateId)
            return
        }
        val created = createRefund.createForFailedProject(ProjectId(message.aggregateId), batchAuditContext())
        log.info("BAT-003 created {} refunds for project {}", created, message.aggregateId)
    }
}
