package com.example.cf.payment.domain.model

import com.example.cf.payment.domain.event.PaymentFailed
import com.example.cf.payment.domain.event.PaymentReconciliationRequired
import com.example.cf.payment.domain.event.PaymentSucceeded
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.money.Money
import java.time.Instant

/** 決済状態と許可遷移（詳細設計 §4.4）。 */
enum class PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    REFUND_PENDING,
    REFUNDED,
    REFUND_FAILED,
    ;

    fun canTransitionTo(next: PaymentStatus): Boolean = next in allowedTransitions()

    private fun allowedTransitions(): Set<PaymentStatus> = when (this) {
        CREATED -> setOf(PROCESSING)
        PROCESSING -> setOf(SUCCEEDED, FAILED, UNKNOWN)
        SUCCEEDED -> setOf(REFUND_PENDING)
        FAILED -> emptySet()
        UNKNOWN -> setOf(SUCCEEDED, FAILED)
        REFUND_PENDING -> setOf(REFUNDED, REFUND_FAILED)
        REFUNDED -> emptySet()
        REFUND_FAILED -> setOf(REFUND_PENDING)
    }
}

/**
 * Payment集約ルート（詳細設計 §4.4）。
 * 外部決済APIの呼出しはOutbox Worker（工程7）が行い、本集約は内部状態のみを持つ。
 */
class Payment(
    val id: PaymentId,
    val supportId: SupportId,
    val provider: String,
    providerPaymentId: String?,
    val amount: Money,
    status: PaymentStatus,
    failureCode: String?,
    processedAt: Instant?,
    version: Version,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var providerPaymentId: String? = providerPaymentId
        private set
    var status: PaymentStatus = status
        private set
    var failureCode: String? = failureCode
        private set
    var processedAt: Instant? = processedAt
        private set
    var version: Version = version
        private set
    var updatedAt: Instant = updatedAt
        private set

    /** 外部決済処理の開始（CREATED→PROCESSING）。外部IDが採番されたら記録する。 */
    fun startProcessing(providerPaymentId: String?, now: Instant) {
        transition(PaymentStatus.PROCESSING, now)
        if (providerPaymentId != null) {
            this.providerPaymentId = providerPaymentId
        }
    }

    /** 決済成功（§4.4）。 */
    fun succeed(providerPaymentId: String?, now: Instant): PaymentSucceeded {
        transition(PaymentStatus.SUCCEEDED, now)
        if (providerPaymentId != null) {
            this.providerPaymentId = providerPaymentId
        }
        processedAt = now
        return PaymentSucceeded(id, supportId, now)
    }

    /** 決済失敗（§4.4）。 */
    fun fail(failureCode: String?, now: Instant): PaymentFailed {
        transition(PaymentStatus.FAILED, now)
        this.failureCode = failureCode
        processedAt = now
        return PaymentFailed(id, supportId, failureCode, now)
    }

    /** 結果不明（BAT-007 決済照合の対象）。 */
    fun markUnknown(now: Instant): PaymentReconciliationRequired {
        transition(PaymentStatus.UNKNOWN, now)
        return PaymentReconciliationRequired(id, supportId, now)
    }

    /** 返金要求済み（工程8で使用）。 */
    fun requireRefund(now: Instant) {
        transition(PaymentStatus.REFUND_PENDING, now)
    }

    /** 返金完了（工程8で使用）。 */
    fun markRefunded(now: Instant) {
        transition(PaymentStatus.REFUNDED, now)
        processedAt = now
    }

    private fun transition(next: PaymentStatus, now: Instant) {
        if (!status.canTransitionTo(next)) {
            throw InvalidStateException(
                "PAYMENT_INVALID_STATE",
                "Payment ${id.value} cannot transition from $status to $next",
            )
        }
        status = next
        version = version.increment()
        updatedAt = now
    }

    companion object {
        /** 内部生成（UC-FD-001。§5.3: Support作成と同一トランザクション）。 */
        fun create(
            id: PaymentId,
            supportId: SupportId,
            provider: String,
            amount: Money,
            now: Instant,
        ): Payment {
            require(amount.amount > 0) { "payment amount must be positive" }
            return Payment(
                id = id,
                supportId = supportId,
                provider = provider,
                providerPaymentId = null,
                amount = amount,
                status = PaymentStatus.CREATED,
                failureCode = null,
                processedAt = null,
                version = Version(0),
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
