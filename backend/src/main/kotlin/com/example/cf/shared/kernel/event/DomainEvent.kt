package com.example.cf.shared.kernel.event

import com.example.cf.shared.kernel.id.CorrelationId
import com.example.cf.shared.kernel.id.EventId
import java.time.Instant

/**
 * ドメインイベントマーカー。業務上意味のある完了事実として過去形で命名する（基本設計 §4.5）。
 */
interface DomainEvent {
    /** イベント型名（例: ProjectSubmittedForReview）。Outboxのevent_typeに使用。 */
    val eventType: String

    /** 発生元集約の種別（例: Project）。 */
    val aggregateType: String

    /** 発生元集約のID文字列。 */
    val aggregateId: String
}

/**
 * イベント共通エンベロープ（詳細設計 §4.9）。
 */
data class DomainEventEnvelope<T : DomainEvent>(
    val eventId: EventId,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val occurredAt: Instant,
    val correlationId: CorrelationId,
    val payload: T,
)
