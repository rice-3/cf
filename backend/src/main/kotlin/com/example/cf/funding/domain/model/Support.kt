package com.example.cf.funding.domain.model

import com.example.cf.funding.domain.event.RefundRequired
import com.example.cf.funding.domain.event.SupportCancelled
import com.example.cf.funding.domain.event.SupportConfirmed
import com.example.cf.funding.domain.event.SupportPaymentFailed
import com.example.cf.funding.domain.event.SupportRequested
import com.example.cf.shared.kernel.IdempotencyKey
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.AccessDeniedException
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.OptimisticLockConflictException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.SupportItemId
import com.example.cf.shared.kernel.id.UserId
import com.example.cf.shared.kernel.money.Money
import java.time.Instant

/**
 * 支援状態（基本設計 §3.5）。
 *
 * 詳細設計 §4.3 は PAYMENT_PENDING / AUTHORIZED / CONFIRMED / REFUND_PENDING という
 * 別名称を用いているが、上位文書である基本設計を正とする。
 * オーソリ済みを表すAUTHORIZEDは基本設計に存在せず、決済保留中はPENDINGを維持する
 * （基本設計 §3.4「保留：PENDINGを維持して照会・再処理」）。
 */
enum class SupportStatus {
    /** 支援受付済み、決済確定前。 */
    PENDING,

    /** 決済成功。 */
    PAID,

    /** 決済失敗。 */
    PAYMENT_FAILED,

    /** 取消要求済み。 */
    CANCEL_REQUESTED,

    /** 取消完了。 */
    CANCELLED,

    /** 返金要求済み。 */
    REFUND_REQUESTED,

    /** 返金処理中。 */
    REFUNDING,

    /** 返金完了。 */
    REFUNDED,

    /** 返金失敗。 */
    REFUND_FAILED,
}

/** 支援明細（詳細設計 §8.11）。リターンなし（追加支援のみ）の場合はrewardPlanId=null。 */
data class SupportItem(
    val id: SupportItemId,
    val rewardPlanId: RewardPlanId?,
    val quantity: Int,
    val unitAmount: Money,
    val amount: Money,
) {
    init {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(amount.amount > 0) { "amount must be positive" }
    }
}

/**
 * Support集約ルート（詳細設計 §4.3）。
 */
class Support(
    val id: SupportId,
    val projectId: ProjectId,
    val supporterUserId: UserId,
    val amount: Money,
    val idempotencyKey: IdempotencyKey,
    val contactEmail: String,
    val items: List<SupportItem>,
    status: SupportStatus,
    paymentId: PaymentId?,
    version: Version,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var status: SupportStatus = status
        private set
    var paymentId: PaymentId? = paymentId
        private set
    var version: Version = version
        private set
    var updatedAt: Instant = updatedAt
        private set

    fun requireVersion(expected: Version) {
        if (version != expected) {
            throw OptimisticLockConflictException(
                "Support ${id.value} was updated by another user (expected=${expected.value}, actual=${version.value})",
            )
        }
    }

    fun requireOwnedBy(userId: UserId) {
        if (supporterUserId != userId) {
            throw AccessDeniedException(message = "Support ${id.value} is not owned by the user")
        }
    }

    /** Payment生成後の紐付け（UC-FD-001。同一トランザクション内でのみ呼ばれる）。 */
    fun attachPayment(payment: PaymentId, now: Instant) {
        check(paymentId == null) { "Payment is already attached to support ${id.value}" }
        paymentId = payment
        updatedAt = now
    }

    /** 決済成功による確定（基本設計 §3.4「成功：PAID」）。 */
    fun confirm(now: Instant): SupportConfirmed {
        transition(SupportStatus.PAID, from = setOf(SupportStatus.PENDING), now = now)
        return SupportConfirmed(id, projectId, now)
    }

    /** 決済失敗（基本設計 §3.4「失敗：PAYMENT_FAILED」）。 */
    fun failPayment(now: Instant): SupportPaymentFailed {
        transition(SupportStatus.PAYMENT_FAILED, from = setOf(SupportStatus.PENDING), now = now)
        return SupportPaymentFailed(id, projectId, now)
    }

    /**
     * 支援者による取消（API-FD-004、§4.3 cancel）。
     * 決済確定前はCANCELLED、確定後は返金フロー（第2段階 工程8）で処理する
     * CANCEL_REQUESTEDとする。
     */
    fun cancel(now: Instant): SupportCancelled {
        when (status) {
            SupportStatus.PENDING, SupportStatus.PAYMENT_FAILED -> {
                transition(SupportStatus.CANCELLED, from = setOf(status), now = now)
            }
            SupportStatus.PAID -> {
                transition(SupportStatus.CANCEL_REQUESTED, from = setOf(status), now = now)
            }
            else -> throw InvalidStateException(
                "SUPPORT_INVALID_STATE",
                "Support ${id.value} cannot be cancelled in status $status",
            )
        }
        return SupportCancelled(id, projectId, now)
    }

    /** 不成立等による返金要求（§4.3 requireRefund）。 */
    fun requireRefund(now: Instant): RefundRequired {
        transition(
            SupportStatus.REFUND_REQUESTED,
            from = setOf(SupportStatus.PAID, SupportStatus.CANCEL_REQUESTED),
            now = now,
        )
        return RefundRequired(id, projectId, now)
    }

    /** 返金処理開始（BAT-004 返金実行、工程8で使用）。 */
    fun startRefund(now: Instant) {
        transition(
            SupportStatus.REFUNDING,
            from = setOf(SupportStatus.REFUND_REQUESTED, SupportStatus.REFUND_FAILED),
            now = now,
        )
    }

    /** 返金完了（§4.3 markRefunded）。 */
    fun markRefunded(now: Instant) {
        transition(SupportStatus.REFUNDED, from = setOf(SupportStatus.REFUNDING), now = now)
    }

    /** 返金失敗（基本設計 §3.5 REFUND_FAILED。再試行はBAT-004が行う）。 */
    fun failRefund(now: Instant) {
        transition(SupportStatus.REFUND_FAILED, from = setOf(SupportStatus.REFUNDING), now = now)
    }

    private fun transition(next: SupportStatus, from: Set<SupportStatus>, now: Instant) {
        if (status !in from) {
            throw InvalidStateException(
                "SUPPORT_INVALID_STATE",
                "Support ${id.value} cannot transition from $status to $next",
            )
        }
        status = next
        version = version.increment()
        updatedAt = now
    }

    companion object {
        /** 支援額の業務上限（詳細設計 §5.3: 100,000,000円）。 */
        const val MAX_SUPPORT_AMOUNT: Long = 100_000_000

        /** 支援申込の生成（UC-FD-001 request）。金額検証は§5.3。 */
        fun request(
            id: SupportId,
            projectId: ProjectId,
            supporterUserId: UserId,
            items: List<SupportItem>,
            idempotencyKey: IdempotencyKey,
            contactEmail: String,
            now: Instant,
        ): Support {
            if (items.isEmpty()) {
                throw ValidationException(message = "support must contain at least one item")
            }
            val total = items.fold(Money.ZERO) { acc, item -> acc + item.amount }
            if (total.amount < 1 || total.amount > MAX_SUPPORT_AMOUNT) {
                throw ValidationException(
                    errorCode = "SUPPORT_AMOUNT_INVALID",
                    message = "support amount must be 1..$MAX_SUPPORT_AMOUNT yen",
                )
            }
            if (!EMAIL_PATTERN.matches(contactEmail)) {
                throw ValidationException(message = "contactEmail is invalid")
            }
            return Support(
                id = id,
                projectId = projectId,
                supporterUserId = supporterUserId,
                amount = total,
                idempotencyKey = idempotencyKey,
                contactEmail = contactEmail,
                items = items,
                status = SupportStatus.PENDING,
                paymentId = null,
                version = Version(0),
                createdAt = now,
                updatedAt = now,
            )
        }

        /** 生成イベント（§4.3 SupportRequested）。Payment紐付け後に発行する。 */
        fun requestedEvent(support: Support, paymentId: PaymentId, now: Instant): SupportRequested = SupportRequested(
            supportId = support.id,
            projectId = support.projectId,
            supporterUserId = support.supporterUserId,
            amount = support.amount.amount,
            paymentId = paymentId,
            occurredAt = now,
        )

        private val EMAIL_PATTERN = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
