package com.example.cf.payment.adapter.out.persistence

import com.example.cf.payment.application.PaymentReferenceQuery
import com.example.cf.payment.domain.model.Payment
import com.example.cf.payment.domain.model.PaymentStatus
import com.example.cf.payment.domain.repository.PaymentRepository
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.money.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Instant

/** payment テーブル（詳細設計 §8.12）。 */
@Entity
@Table(name = "payment")
class PaymentJpaEntity(
    @Id
    @Column(name = "payment_id", length = 26)
    var paymentId: String = "",

    @Column(name = "support_id", length = 26, nullable = false)
    var supportId: String = "",

    @Column(name = "provider", length = 30, nullable = false)
    var provider: String = "",

    @Column(name = "provider_payment_id", length = 100)
    var providerPaymentId: String? = null,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    @Column(name = "status", length = 30, nullable = false)
    var status: String = "",

    @Column(name = "failure_code", length = 100)
    var failureCode: String? = null,

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Repository
interface PaymentJpaRepository : JpaRepository<PaymentJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentJpaEntity p where p.paymentId = :id")
    fun findWithLockByPaymentId(@Param("id") id: String): PaymentJpaEntity?

    fun findByProviderAndProviderPaymentId(provider: String, providerPaymentId: String): PaymentJpaEntity?

    /** BAT-007の対象。UNKNOWNのまま滞留した決済を古い順に取得する。 */
    @Query(
        value = """
            select * from payment
             where status = 'UNKNOWN'
             order by updated_at
             limit :limit
        """,
        nativeQuery = true,
    )
    fun findReconcileTargets(@Param("limit") limit: Int): List<PaymentJpaEntity>
}

/**
 * Payment永続化Adapter（Repository / 公開契約PaymentReferenceQueryの実装）。
 */
@Component
class PaymentPersistenceAdapter(
    private val jpaRepository: PaymentJpaRepository,
) : PaymentRepository, PaymentReferenceQuery {

    override fun findById(id: PaymentId): Payment? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByIdForUpdate(id: PaymentId): Payment? =
        jpaRepository.findWithLockByPaymentId(id.value)?.toDomain()

    override fun findByProviderPaymentId(provider: String, providerPaymentId: String): Payment? =
        jpaRepository.findByProviderAndProviderPaymentId(provider, providerPaymentId)?.toDomain()

    override fun findReconcileTargets(limit: Int): List<PaymentId> =
        jpaRepository.findReconcileTargets(limit).map { PaymentId(it.paymentId) }

    override fun save(payment: Payment) {
        val entity = jpaRepository.findById(payment.id.value).orElse(PaymentJpaEntity())
        entity.paymentId = payment.id.value
        entity.supportId = payment.supportId.value
        entity.provider = payment.provider
        entity.providerPaymentId = payment.providerPaymentId
        entity.amount = payment.amount.amount
        entity.status = payment.status.name
        entity.failureCode = payment.failureCode
        entity.processedAt = payment.processedAt
        entity.version = payment.version.value
        entity.createdAt = payment.createdAt
        entity.updatedAt = payment.updatedAt
        jpaRepository.save(entity)
    }

    private fun PaymentJpaEntity.toDomain() = Payment(
        id = PaymentId(paymentId),
        supportId = SupportId(supportId),
        provider = provider,
        providerPaymentId = providerPaymentId,
        amount = Money.of(amount),
        status = PaymentStatus.valueOf(status),
        failureCode = failureCode,
        processedAt = processedAt,
        version = Version(version),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ---- PaymentReferenceQuery（公開契約） -----------------------------------

    override fun findStatuses(paymentIds: Collection<String>): Map<String, String> =
        if (paymentIds.isEmpty()) {
            emptyMap()
        } else {
            jpaRepository.findAllById(paymentIds).associate { it.paymentId to it.status }
        }
}
