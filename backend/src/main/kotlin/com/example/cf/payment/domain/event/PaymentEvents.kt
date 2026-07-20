package com.example.cf.payment.domain.event

import com.example.cf.shared.kernel.event.DomainEvent
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.SupportId
import java.time.Instant

/** Payment集約のドメインイベント（詳細設計 §4.4）。 */
sealed interface PaymentDomainEvent : DomainEvent {
    val paymentId: PaymentId

    override val aggregateType: String get() = "Payment"
    override val aggregateId: String get() = paymentId.value
}

/** 決済要求。Outbox配送（BAT-006）を契機に外部決済APIを呼び出す。 */
data class PaymentRequested(
    override val paymentId: PaymentId,
    val supportId: SupportId,
    val amount: Long,
    val provider: String,
    val occurredAt: Instant,
) : PaymentDomainEvent {
    override val eventType: String = "PaymentRequested"
}

data class PaymentSucceeded(
    override val paymentId: PaymentId,
    val supportId: SupportId,
    val occurredAt: Instant,
) : PaymentDomainEvent {
    override val eventType: String = "PaymentSucceeded"
}

data class PaymentFailed(
    override val paymentId: PaymentId,
    val supportId: SupportId,
    val failureCode: String?,
    val occurredAt: Instant,
) : PaymentDomainEvent {
    override val eventType: String = "PaymentFailed"
}

data class PaymentReconciliationRequired(
    override val paymentId: PaymentId,
    val supportId: SupportId,
    val occurredAt: Instant,
) : PaymentDomainEvent {
    override val eventType: String = "PaymentReconciliationRequired"
}
