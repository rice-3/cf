package com.example.cf.notification.domain.repository

import com.example.cf.notification.domain.model.Notification
import com.example.cf.notification.domain.model.NotificationChannel
import com.example.cf.shared.kernel.id.NotificationId
import java.time.Instant

interface NotificationRepository {
    fun findById(id: NotificationId): Notification?

    fun findByIdForUpdate(id: NotificationId): Notification?

    /** 重複送信防止の確認（§4.6: business_key＋channelに一意制約）。 */
    fun existsByBusinessKey(businessKey: String, channel: NotificationChannel): Boolean

    /**
     * BAT-005 通知送信の対象を取得する。
     * PENDING、または再試行時刻に達したRETRY_WAITを `FOR UPDATE SKIP LOCKED` で取得する（基本設計 §8.3）。
     */
    fun lockSendableBatch(now: Instant, limit: Int): List<Notification>

    fun save(notification: Notification)

    /** 送信試行の記録（notification_delivery、§8.17）。 */
    fun recordDelivery(
        notificationId: NotificationId,
        attemptNo: Int,
        providerMessageId: String?,
        result: String,
        errorCode: String?,
        attemptedAt: Instant,
    )
}
