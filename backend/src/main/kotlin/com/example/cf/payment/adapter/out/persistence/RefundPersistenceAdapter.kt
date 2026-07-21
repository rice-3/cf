package com.example.cf.payment.adapter.out.persistence

import com.example.cf.payment.application.RefundListItem
import com.example.cf.payment.application.RefundSearchQuery
import com.example.cf.payment.domain.model.Refund
import com.example.cf.payment.domain.model.RefundReasonCode
import com.example.cf.payment.domain.model.RefundStatus
import com.example.cf.payment.domain.repository.RefundRepository
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.money.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** refund テーブル（詳細設計 §8.14）。 */
@Entity
@Table(name = "refund")
class RefundJpaEntity(
    @Id
    @Column(name = "refund_id", length = 26)
    var refundId: String = "",

    @Column(name = "payment_id", length = 26, nullable = false)
    var paymentId: String = "",

    @Column(name = "support_id", length = 26, nullable = false)
    var supportId: String = "",

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    @Column(name = "reason_code", length = 50, nullable = false)
    var reasonCode: String = "",

    @Column(name = "comment", length = 2000)
    var comment: String? = null,

    @Column(name = "status", length = 30, nullable = false)
    var status: String = "",

    @Column(name = "provider_refund_id", length = 100)
    var providerRefundId: String? = null,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null,

    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

@Repository
interface RefundJpaRepository : JpaRepository<RefundJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundJpaEntity r where r.refundId = :id")
    fun findWithLockByRefundId(@Param("id") id: String): RefundJpaEntity?

    @Query("select r from RefundJpaEntity r where r.supportId = :supportId and r.status <> 'FAILED'")
    fun findActiveBySupportId(@Param("supportId") supportId: String): RefundJpaEntity?

    /** BAT-004の対象取得。多重起動時も同一行を二重処理しない（基本設計 §8.3）。 */
    @Query(
        value = """
            select * from refund
             where (status = 'REQUESTED'
                    or (status = 'RETRY_WAIT' and next_retry_at is not null and next_retry_at <= :now))
             order by created_at
             for update skip locked
             limit :limit
        """,
        nativeQuery = true,
    )
    fun lockExecutableBatch(@Param("now") now: Instant, @Param("limit") limit: Int): List<RefundJpaEntity>

    @Query(
        """
        select r from RefundJpaEntity r
         where (:status is null or r.status = :status)
        """,
    )
    fun searchForOperations(
        @Param("status") status: String?,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<RefundJpaEntity>
}

@Component
class RefundPersistenceAdapter(
    private val jpaRepository: RefundJpaRepository,
) : RefundRepository,
    RefundSearchQuery {

    override fun findById(id: RefundId): Refund? = jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByIdForUpdate(id: RefundId): Refund? = jpaRepository.findWithLockByRefundId(id.value)?.toDomain()

    override fun findActiveBySupportId(supportId: SupportId): Refund? = jpaRepository.findActiveBySupportId(supportId.value)?.toDomain()

    override fun lockExecutableBatch(now: Instant, limit: Int): List<Refund> = jpaRepository.lockExecutableBatch(now, limit).map { it.toDomain() }

    override fun save(refund: Refund) {
        val entity = jpaRepository.findById(refund.id.value).orElse(RefundJpaEntity())
        entity.refundId = refund.id.value
        entity.paymentId = refund.paymentId.value
        entity.supportId = refund.supportId.value
        entity.amount = refund.amount.amount
        entity.reasonCode = refund.reasonCode.name
        entity.comment = refund.comment
        entity.status = refund.status.name
        entity.providerRefundId = refund.providerRefundId
        entity.retryCount = refund.retryCount
        entity.nextRetryAt = refund.nextRetryAt
        entity.version = refund.version.value
        entity.createdAt = refund.createdAt
        entity.updatedAt = refund.updatedAt
        jpaRepository.save(entity)
    }

    // ---- RefundSearchQuery（運用者横断検索、SCR-061） -------------------------

    @Transactional(readOnly = true)
    override fun search(status: RefundStatus?, page: Int, size: Int): PageResult<RefundListItem> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = jpaRepository.searchForOperations(status?.name, pageable)
        return PageResult.of(
            items = result.content.map {
                RefundListItem(
                    refundId = it.refundId,
                    supportId = it.supportId,
                    paymentId = it.paymentId,
                    amount = it.amount,
                    reasonCode = it.reasonCode,
                    status = RefundStatus.valueOf(it.status),
                    retryCount = it.retryCount,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    private fun RefundJpaEntity.toDomain() = Refund(
        id = RefundId(refundId),
        paymentId = PaymentId(paymentId),
        supportId = SupportId(supportId),
        amount = Money.of(amount),
        reasonCode = RefundReasonCode.valueOf(reasonCode),
        comment = comment,
        status = RefundStatus.valueOf(status),
        providerRefundId = providerRefundId,
        retryCount = retryCount,
        nextRetryAt = nextRetryAt,
        version = Version(version),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
