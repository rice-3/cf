package com.example.cf.notification.adapter.out.persistence

import com.example.cf.notification.domain.model.Notification
import com.example.cf.notification.domain.model.NotificationChannel
import com.example.cf.notification.domain.model.NotificationStatus
import com.example.cf.notification.domain.repository.NotificationRepository
import com.example.cf.shared.kernel.id.NotificationId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.id.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Instant

/** notification テーブル（詳細設計 §8.16）。 */
@Entity
@Table(name = "notification")
class NotificationJpaEntity(
    @Id
    @Column(name = "notification_id", length = 26)
    var notificationId: String = "",

    @Column(name = "business_key", length = 200, nullable = false)
    var businessKey: String = "",

    @Column(name = "channel", length = 30, nullable = false)
    var channel: String = "",

    @Column(name = "template_id", length = 100, nullable = false)
    var templateId: String = "",

    @Column(name = "recipient_user_id", length = 26)
    var recipientUserId: String? = null,

    @Column(name = "recipient_address", length = 320)
    var recipientAddress: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false)
    var variables: Map<String, Any?> = emptyMap(),

    @Column(name = "status", length = 30, nullable = false)
    var status: String = "",

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

/** notification_delivery テーブル（詳細設計 §8.17）。 */
@Entity
@Table(name = "notification_delivery")
class NotificationDeliveryJpaEntity(
    @Id
    @Column(name = "delivery_id", length = 26)
    var deliveryId: String = "",

    @Column(name = "notification_id", length = 26, nullable = false)
    var notificationId: String = "",

    @Column(name = "attempt_no", nullable = false)
    var attemptNo: Int = 0,

    @Column(name = "provider_message_id", length = 200)
    var providerMessageId: String? = null,

    @Column(name = "result", length = 30, nullable = false)
    var result: String = "",

    @Column(name = "error_code", length = 100)
    var errorCode: String? = null,

    @Column(name = "attempted_at", nullable = false)
    var attemptedAt: Instant = Instant.EPOCH,
)

@Repository
interface NotificationJpaRepository : JpaRepository<NotificationJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NotificationJpaEntity n where n.notificationId = :id")
    fun findWithLockByNotificationId(@Param("id") id: String): NotificationJpaEntity?

    fun existsByBusinessKeyAndChannel(businessKey: String, channel: String): Boolean

    /** BAT-005の対象取得。多重起動時も同一行を二重送信しない（基本設計 §8.3）。 */
    @Query(
        value = """
            select * from notification
             where (status = 'PENDING'
                    or (status = 'RETRY_WAIT' and next_retry_at is not null and next_retry_at <= :now))
             order by created_at
             for update skip locked
             limit :limit
        """,
        nativeQuery = true,
    )
    fun lockSendableBatch(@Param("now") now: Instant, @Param("limit") limit: Int): List<NotificationJpaEntity>
}

@Repository
interface NotificationDeliveryJpaRepository : JpaRepository<NotificationDeliveryJpaEntity, String>

@Component
class NotificationPersistenceAdapter(
    private val jpaRepository: NotificationJpaRepository,
    private val deliveryRepository: NotificationDeliveryJpaRepository,
    private val idGenerator: UlidGenerator,
) : NotificationRepository {

    override fun findById(id: NotificationId): Notification? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByIdForUpdate(id: NotificationId): Notification? =
        jpaRepository.findWithLockByNotificationId(id.value)?.toDomain()

    override fun existsByBusinessKey(businessKey: String, channel: NotificationChannel): Boolean =
        jpaRepository.existsByBusinessKeyAndChannel(businessKey, channel.name)

    override fun lockSendableBatch(now: Instant, limit: Int): List<Notification> =
        jpaRepository.lockSendableBatch(now, limit).map { it.toDomain() }

    override fun save(notification: Notification) {
        val entity = jpaRepository.findById(notification.id.value).orElse(NotificationJpaEntity())
        entity.notificationId = notification.id.value
        entity.businessKey = notification.businessKey
        entity.channel = notification.channel.name
        entity.templateId = notification.templateId
        entity.recipientUserId = notification.recipientUserId?.value
        entity.recipientAddress = notification.recipientAddress
        entity.variables = notification.variables
        entity.status = notification.status.name
        entity.retryCount = notification.retryCount
        entity.nextRetryAt = notification.nextRetryAt
        entity.createdAt = notification.createdAt
        entity.updatedAt = notification.updatedAt
        jpaRepository.save(entity)
    }

    override fun recordDelivery(
        notificationId: NotificationId,
        attemptNo: Int,
        providerMessageId: String?,
        result: String,
        errorCode: String?,
        attemptedAt: Instant,
    ) {
        deliveryRepository.save(
            NotificationDeliveryJpaEntity(
                deliveryId = idGenerator.next(),
                notificationId = notificationId.value,
                attemptNo = attemptNo,
                providerMessageId = providerMessageId,
                result = result,
                errorCode = errorCode,
                attemptedAt = attemptedAt,
            ),
        )
    }

    private fun NotificationJpaEntity.toDomain() = Notification(
        id = NotificationId(notificationId),
        businessKey = businessKey,
        channel = NotificationChannel.valueOf(channel),
        templateId = templateId,
        recipientUserId = recipientUserId?.let { UserId(it) },
        recipientAddress = recipientAddress,
        variables = variables,
        status = NotificationStatus.valueOf(status),
        retryCount = retryCount,
        nextRetryAt = nextRetryAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
