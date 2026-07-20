package com.example.cf.payment.domain.event

import com.example.cf.shared.kernel.event.DomainEvent
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.kernel.id.SupportId
import java.time.Instant

/** Refund集約のドメインイベント（詳細設計 §4.5）。 */
sealed interface RefundDomainEvent : DomainEvent {
    val refundId: RefundId

    override val aggregateType: String get() = "Refund"
    override val aggregateId: String get() = refundId.value
}

/** 返金成功（基本設計 §4.6。購読者: Funding、Notification、Audit）。 */
data class RefundCompleted(
    override val refundId: RefundId,
    val supportId: SupportId,
    val paymentId: PaymentId,
    val amount: Long,
    val occurredAt: Instant,
) : RefundDomainEvent {
    override val eventType: String = "RefundCompleted"
}

/** 返金の恒久失敗。再試行上限超過時のみ発行し、OPERATOR対応の契機とする（基本設計 §8.2）。 */
data class RefundFailed(
    override val refundId: RefundId,
    val supportId: SupportId,
    val paymentId: PaymentId,
    val errorCode: String?,
    val occurredAt: Instant,
) : RefundDomainEvent {
    override val eventType: String = "RefundFailed"
}
