package com.example.cf.payment.domain.model

import com.example.cf.payment.domain.event.RefundCompleted
import com.example.cf.payment.domain.event.RefundFailed
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.money.Money
import java.time.Duration
import java.time.Instant

/** 返金状態（詳細設計 §4.5）。 */
enum class RefundStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    RETRY_WAIT,
    FAILED,
}

/** 返金理由区分（詳細設計 §6.9）。 */
enum class RefundReasonCode {
    /** 募集不成立による自動返金（BAT-003）。 */
    PROJECT_FAILED,

    /** 運用判断による返金。comment必須。 */
    OPERATIONAL,

    /** 支援者都合の取消による返金。 */
    USER_CANCEL,
}

/**
 * Refund集約ルート（詳細設計 §4.5）。
 * 基本設計 §4.1 のコンテキスト一覧に返金は独立して存在しないため、Paymentコンテキストに属する。
 */
class Refund(
    val id: RefundId,
    val paymentId: PaymentId,
    val supportId: SupportId,
    val amount: Money,
    val reasonCode: RefundReasonCode,
    val comment: String?,
    status: RefundStatus,
    providerRefundId: String?,
    retryCount: Int,
    nextRetryAt: Instant?,
    version: Version,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var status: RefundStatus = status
        private set
    var providerRefundId: String? = providerRefundId
        private set
    var retryCount: Int = retryCount
        private set
    var nextRetryAt: Instant? = nextRetryAt
        private set
    var version: Version = version
        private set
    var updatedAt: Instant = updatedAt
        private set

    /** 返金実行開始（§4.5 start）。BAT-004が呼ぶ。 */
    fun start(now: Instant) {
        if (status != RefundStatus.REQUESTED && status != RefundStatus.RETRY_WAIT) {
            throw InvalidStateException(
                "REFUND_INVALID_STATE",
                "Refund ${id.value} cannot start in status $status",
            )
        }
        status = RefundStatus.PROCESSING
        nextRetryAt = null
        touch(now)
    }

    /** 返金成功（§4.5 succeed）。 */
    fun succeed(providerRefundId: String?, now: Instant): RefundCompleted {
        requireProcessing()
        if (providerRefundId != null) {
            this.providerRefundId = providerRefundId
        }
        status = RefundStatus.SUCCEEDED
        nextRetryAt = null
        touch(now)
        return RefundCompleted(id, supportId, paymentId, amount.amount, now)
    }

    /**
     * 返金失敗（§4.5 fail）。再試行上限未満はRETRY_WAIT、超過でFAILED（§9.2: 最大8回）。
     * FAILED到達時のみRefundFailedを返し、OPERATORへの通知契機とする（基本設計 §8.2）。
     */
    fun fail(errorCode: String?, now: Instant): RefundFailed? {
        requireProcessing()
        retryCount += 1
        return if (retryCount >= MAX_RETRY_COUNT) {
            status = RefundStatus.FAILED
            nextRetryAt = null
            touch(now)
            RefundFailed(id, supportId, paymentId, errorCode, now)
        } else {
            status = RefundStatus.RETRY_WAIT
            nextRetryAt = now.plus(backoff(retryCount))
            touch(now)
            null
        }
    }

    /** OPERATORによる再実行要求（§4.5 retry、API-RF-002）。 */
    fun retry(now: Instant) {
        if (status != RefundStatus.RETRY_WAIT && status != RefundStatus.FAILED) {
            throw InvalidStateException(
                "REFUND_INVALID_STATE",
                "Refund ${id.value} cannot be retried in status $status",
            )
        }
        if (retryCount >= MAX_RETRY_COUNT && status == RefundStatus.RETRY_WAIT) {
            throw InvalidStateException(
                "REFUND_RETRY_EXHAUSTED",
                "Refund ${id.value} exceeded the retry limit",
            )
        }
        status = RefundStatus.REQUESTED
        nextRetryAt = null
        touch(now)
    }

    private fun requireProcessing() {
        if (status != RefundStatus.PROCESSING) {
            throw InvalidStateException(
                "REFUND_INVALID_STATE",
                "Refund ${id.value} is not processing: $status",
            )
        }
    }

    private fun touch(now: Instant) {
        version = version.increment()
        updatedAt = now
    }

    companion object {
        /** 返金の再試行上限（詳細設計 §9.2）。 */
        const val MAX_RETRY_COUNT = 8

        /** 再試行間隔 1m〜24h（詳細設計 §9.2）。 */
        private fun backoff(retryCount: Int): Duration = when (retryCount) {
            1 -> Duration.ofMinutes(1)
            2 -> Duration.ofMinutes(5)
            3 -> Duration.ofMinutes(15)
            4 -> Duration.ofHours(1)
            5 -> Duration.ofHours(3)
            6 -> Duration.ofHours(6)
            7 -> Duration.ofHours(12)
            else -> Duration.ofHours(24)
        }

        /** 返金要求（§4.5 request）。Payment=SUCCEEDEDかつ未返金であることは呼出し側が検証する。 */
        fun request(
            id: RefundId,
            paymentId: PaymentId,
            supportId: SupportId,
            amount: Money,
            reasonCode: RefundReasonCode,
            comment: String?,
            now: Instant,
        ): Refund {
            require(amount.amount > 0) { "refund amount must be positive" }
            if (reasonCode == RefundReasonCode.OPERATIONAL && comment.isNullOrBlank()) {
                throw InvalidStateException(
                    "REFUND_COMMENT_REQUIRED",
                    "comment is required for operational refunds",
                )
            }
            return Refund(
                id = id,
                paymentId = paymentId,
                supportId = supportId,
                amount = amount,
                reasonCode = reasonCode,
                comment = comment,
                status = RefundStatus.REQUESTED,
                providerRefundId = null,
                retryCount = 0,
                nextRetryAt = null,
                version = Version(0),
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
