package com.example.cf.funding.domain.event

import com.example.cf.shared.kernel.event.DomainEvent
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/** Support集約のドメインイベント（詳細設計 §4.3）。 */
sealed interface SupportDomainEvent : DomainEvent {
    val supportId: SupportId

    override val aggregateType: String get() = "Support"
    override val aggregateId: String get() = supportId.value
}

data class SupportRequested(
    override val supportId: SupportId,
    val projectId: ProjectId,
    val supporterUserId: UserId,
    val amount: Long,
    val paymentId: PaymentId,
    val occurredAt: Instant,
) : SupportDomainEvent {
    override val eventType: String = "SupportRequested"
}

data class SupportConfirmed(
    override val supportId: SupportId,
    val projectId: ProjectId,
    val occurredAt: Instant,
) : SupportDomainEvent {
    override val eventType: String = "SupportConfirmed"
}

data class SupportPaymentFailed(
    override val supportId: SupportId,
    val projectId: ProjectId,
    val occurredAt: Instant,
) : SupportDomainEvent {
    override val eventType: String = "SupportPaymentFailed"
}

data class SupportCancelled(
    override val supportId: SupportId,
    val projectId: ProjectId,
    val occurredAt: Instant,
) : SupportDomainEvent {
    override val eventType: String = "SupportCancelled"
}

data class RefundRequired(
    override val supportId: SupportId,
    val projectId: ProjectId,
    val occurredAt: Instant,
) : SupportDomainEvent {
    override val eventType: String = "RefundRequired"
}
