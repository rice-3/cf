package com.example.cf.notification.domain.model

import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.id.NotificationId
import com.example.cf.shared.kernel.id.UserId
import java.time.Duration
import java.time.Instant

/** 通知チャネル（詳細設計 §4.6）。 */
enum class NotificationChannel {
    EMAIL,
    IN_APP,
}

/** 通知状態（詳細設計 §4.6）。 */
enum class NotificationStatus {
    PENDING,
    SENDING,
    SENT,
    RETRY_WAIT,
    FAILED,
}

/**
 * Notification集約ルート（詳細設計 §4.6）。
 *
 * 本文はテンプレートIDと変数のみを保持し、本文そのものは保持しない。
 * 変数には必要最小限の情報だけを入れ、ログへ宛先を平文出力しない（§10.3）。
 */
class Notification(
    val id: NotificationId,
    /** 業務事象の一意キー。channelとの組で重複送信を防ぐ（§4.6）。 */
    val businessKey: String,
    val channel: NotificationChannel,
    val templateId: String,
    val recipientUserId: UserId?,
    val recipientAddress: String?,
    val variables: Map<String, Any?>,
    status: NotificationStatus,
    retryCount: Int,
    nextRetryAt: Instant?,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var status: NotificationStatus = status
        private set
    var retryCount: Int = retryCount
        private set
    var nextRetryAt: Instant? = nextRetryAt
        private set
    var updatedAt: Instant = updatedAt
        private set

    /** 送信開始（BAT-005）。PENDINGまたは再試行時刻に達したRETRY_WAITから遷移する。 */
    fun startSending(now: Instant) {
        if (status != NotificationStatus.PENDING && status != NotificationStatus.RETRY_WAIT) {
            throw InvalidStateException(
                "NOTIFICATION_INVALID_STATE",
                "Notification ${id.value} cannot be sent in status $status",
            )
        }
        status = NotificationStatus.SENDING
        nextRetryAt = null
        updatedAt = now
    }

    fun markSent(now: Instant) {
        requireSending()
        status = NotificationStatus.SENT
        nextRetryAt = null
        updatedAt = now
    }

    /**
     * 送信失敗。上限未満はRETRY_WAIT、超過でFAILED（§9.2: SES最大5回）。
     * 業務エラー（宛先不正など）は再試行しても解決しないため即FAILEDとする（基本設計 §8.2）。
     */
    fun fail(permanent: Boolean, now: Instant) {
        requireSending()
        retryCount += 1
        if (permanent || retryCount >= MAX_RETRY_COUNT) {
            status = NotificationStatus.FAILED
            nextRetryAt = null
        } else {
            status = NotificationStatus.RETRY_WAIT
            nextRetryAt = now.plus(backoff(retryCount))
        }
        updatedAt = now
    }

    private fun requireSending() {
        if (status != NotificationStatus.SENDING) {
            throw InvalidStateException(
                "NOTIFICATION_INVALID_STATE",
                "Notification ${id.value} is not sending: $status",
            )
        }
    }

    companion object {
        /** 通知送信の再試行上限（詳細設計 §9.2）。 */
        const val MAX_RETRY_COUNT = 5

        /** 再試行間隔 1m, 5m, 15m, 1h, 6h（詳細設計 §9.2）。 */
        private fun backoff(retryCount: Int): Duration = when (retryCount) {
            1 -> Duration.ofMinutes(1)
            2 -> Duration.ofMinutes(5)
            3 -> Duration.ofMinutes(15)
            4 -> Duration.ofHours(1)
            else -> Duration.ofHours(6)
        }

        fun create(
            id: NotificationId,
            businessKey: String,
            channel: NotificationChannel,
            templateId: String,
            recipientUserId: UserId?,
            recipientAddress: String?,
            variables: Map<String, Any?>,
            now: Instant,
        ): Notification {
            require(businessKey.isNotBlank() && businessKey.length <= 200) {
                "businessKey must be 1..200 characters"
            }
            if (channel == NotificationChannel.EMAIL && recipientAddress.isNullOrBlank()) {
                throw InvalidStateException(
                    "NOTIFICATION_RECIPIENT_REQUIRED",
                    "recipientAddress is required for EMAIL notifications",
                )
            }
            return Notification(
                id = id,
                businessKey = businessKey,
                channel = channel,
                templateId = templateId,
                recipientUserId = recipientUserId,
                recipientAddress = recipientAddress,
                variables = variables,
                status = NotificationStatus.PENDING,
                retryCount = 0,
                nextRetryAt = null,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
