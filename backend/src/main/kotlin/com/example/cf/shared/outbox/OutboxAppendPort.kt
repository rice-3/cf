package com.example.cf.shared.outbox

import com.example.cf.shared.kernel.event.DomainEvent
import com.example.cf.shared.kernel.id.CorrelationId

/**
 * Transactional Outbox 追記Port（基本設計 §4.7、詳細設計 §5.1-8）。
 * 業務更新と同一トランザクションで outbox_event へ記録し、
 * 配送は BAT-006 Outbox Worker が行う（基本設計 §8.1）。
 */
interface OutboxAppendPort {
    fun append(event: DomainEvent, correlationId: CorrelationId)
}
