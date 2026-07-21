package com.example.cf.funding.adapter.out.persistence

import com.example.cf.funding.application.OperationsSupportListItem
import com.example.cf.funding.application.OperationsSupportSearchQuery
import com.example.cf.funding.application.RefundTarget
import com.example.cf.funding.application.SupportDetailView
import com.example.cf.funding.application.SupportItemView
import com.example.cf.funding.application.SupportListItem
import com.example.cf.funding.application.SupportReferenceQuery
import com.example.cf.funding.application.SupportSearchQuery
import com.example.cf.funding.domain.model.Support
import com.example.cf.funding.domain.model.SupportItem
import com.example.cf.funding.domain.model.SupportStatus
import com.example.cf.funding.domain.repository.SupportRepository
import com.example.cf.payment.application.PaymentReferenceQuery
import com.example.cf.project.application.query.ProjectReferenceQuery
import com.example.cf.shared.kernel.IdempotencyKey
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.SupportItemId
import com.example.cf.shared.kernel.id.UserId
import com.example.cf.shared.kernel.money.Money
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.LockModeType
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
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

/** 返金可能な支援状態（基本設計 §3.5）。決済確定済み、または確定後の取消要求済み。 */
private val REFUNDABLE_STATUSES = setOf(SupportStatus.PAID.name, SupportStatus.CANCEL_REQUESTED.name)

/** support テーブル（詳細設計 §8.10）。 */
@Entity
@Table(name = "support")
class SupportJpaEntity(
    @Id
    @Column(name = "support_id", length = 26)
    var supportId: String = "",

    @Column(name = "project_id", length = 26, nullable = false)
    var projectId: String = "",

    @Column(name = "supporter_user_id", length = 26, nullable = false)
    var supporterUserId: String = "",

    @Column(name = "support_amount", nullable = false)
    var supportAmount: Long = 0,

    @Column(name = "status", length = 30, nullable = false)
    var status: String = "",

    @Column(name = "idempotency_key", length = 100, nullable = false)
    var idempotencyKey: String = "",

    @Column(name = "payment_id", length = 26)
    var paymentId: String? = null,

    @Column(name = "contact_email", length = 320, nullable = false)
    var contactEmail: String = "",

    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,

    @OneToMany(
        mappedBy = "support",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    var items: MutableList<SupportItemJpaEntity> = mutableListOf(),
)

/** support_item テーブル（詳細設計 §8.11）。 */
@Entity
@Table(name = "support_item")
class SupportItemJpaEntity(
    @Id
    @Column(name = "support_item_id", length = 26)
    var supportItemId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "support_id", nullable = false)
    var support: SupportJpaEntity? = null,

    @Column(name = "reward_plan_id", length = 26)
    var rewardPlanId: String? = null,

    @Column(name = "quantity", nullable = false)
    var quantity: Int = 0,

    @Column(name = "unit_amount", nullable = false)
    var unitAmount: Long = 0,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,
)

@Repository
interface SupportJpaRepository : JpaRepository<SupportJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SupportJpaEntity s where s.supportId = :id")
    fun findWithLockBySupportId(@Param("id") id: String): SupportJpaEntity?

    fun findBySupporterUserIdOrderByCreatedAtDesc(
        supporterUserId: String,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<SupportJpaEntity>

    /** 決済確定済みの合計額（BAT-002 成立判定）。 */
    @Query(
        """
        select coalesce(sum(s.supportAmount), 0) from SupportJpaEntity s
         where s.projectId = :projectId and s.status = 'PAID'
        """,
    )
    fun sumPaidAmount(@Param("projectId") projectId: String): Long

    /** 返金対象（BAT-003）。決済確定済み・取消要求済みで、Payment紐付けがあるもの。 */
    @Query(
        """
        select s from SupportJpaEntity s
         where s.projectId = :projectId
           and s.status in ('PAID', 'CANCEL_REQUESTED')
           and s.paymentId is not null
        """,
    )
    fun findRefundTargets(@Param("projectId") projectId: String): List<SupportJpaEntity>

    /** 運用者向け横断検索（SCR-060）。status/projectId は任意（null で無条件）。 */
    @Query(
        """
        select s from SupportJpaEntity s
         where (:status is null or s.status = :status)
           and (:projectId is null or s.projectId = :projectId)
        """,
    )
    fun searchForOperations(
        @Param("status") status: String?,
        @Param("projectId") projectId: String?,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<SupportJpaEntity>
}

/**
 * Support永続化Adapter（Repository / Query Port実装）。
 */
@Component
class SupportPersistenceAdapter(
    private val jpaRepository: SupportJpaRepository,
    private val projectReferenceQuery: ProjectReferenceQuery,
    private val paymentReferenceQuery: PaymentReferenceQuery,
) : SupportRepository,
    SupportSearchQuery,
    OperationsSupportSearchQuery,
    SupportReferenceQuery {

    // ---- SupportReferenceQuery（公開契約、基本設計 §4.1） --------------------

    @Transactional(readOnly = true)
    override fun sumPaidAmount(projectId: ProjectId): Long = jpaRepository.sumPaidAmount(projectId.value)

    @Transactional(readOnly = true)
    override fun findRefundTargets(projectId: ProjectId): List<RefundTarget> = jpaRepository.findRefundTargets(projectId.value).mapNotNull { entity ->
        entity.paymentId?.let {
            RefundTarget(
                supportId = entity.supportId,
                paymentId = it,
                amount = entity.supportAmount,
                supporterUserId = entity.supporterUserId,
            )
        }
    }

    @Transactional(readOnly = true)
    override fun findRefundTarget(supportId: SupportId): RefundTarget? = jpaRepository.findById(supportId.value).orElse(null)
        ?.takeIf { it.status in REFUNDABLE_STATUSES }
        ?.let { entity ->
            entity.paymentId?.let {
                RefundTarget(
                    supportId = entity.supportId,
                    paymentId = it,
                    amount = entity.supportAmount,
                    supporterUserId = entity.supporterUserId,
                )
            }
        }

    @Transactional(readOnly = true)
    override fun findSupporterUserId(supportId: SupportId): String? = jpaRepository.findById(supportId.value).orElse(null)?.supporterUserId

    // ---- SupportRepository ---------------------------------------------------

    override fun findById(id: SupportId): Support? = jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByIdForUpdate(id: SupportId): Support? = jpaRepository.findWithLockBySupportId(id.value)?.toDomain()

    override fun save(support: Support) {
        val entity = jpaRepository.findById(support.id.value).orElse(SupportJpaEntity())
        entity.supportId = support.id.value
        entity.projectId = support.projectId.value
        entity.supporterUserId = support.supporterUserId.value
        entity.supportAmount = support.amount.amount
        entity.status = support.status.name
        entity.idempotencyKey = support.idempotencyKey.value
        entity.paymentId = support.paymentId?.value
        entity.contactEmail = support.contactEmail
        entity.version = support.version.value
        entity.createdAt = support.createdAt
        entity.updatedAt = support.updatedAt

        // 明細は生成時のみで以後不変（§4.3）。既存があれば作り直さない。
        if (entity.items.isEmpty()) {
            support.items.forEach { item ->
                entity.items.add(
                    SupportItemJpaEntity(
                        supportItemId = item.id.value,
                        support = entity,
                        rewardPlanId = item.rewardPlanId?.value,
                        quantity = item.quantity,
                        unitAmount = item.unitAmount.amount,
                        amount = item.amount.amount,
                    ),
                )
            }
        }
        jpaRepository.save(entity)
    }

    private fun SupportJpaEntity.toDomain() = Support(
        id = SupportId(supportId),
        projectId = ProjectId(projectId),
        supporterUserId = UserId(supporterUserId),
        amount = Money.of(supportAmount),
        idempotencyKey = IdempotencyKey(idempotencyKey),
        contactEmail = contactEmail,
        items = items.map {
            SupportItem(
                id = SupportItemId(it.supportItemId),
                rewardPlanId = it.rewardPlanId?.let { id -> RewardPlanId(id) },
                quantity = it.quantity,
                unitAmount = Money.of(it.unitAmount),
                amount = Money.of(it.amount),
            )
        },
        status = SupportStatus.valueOf(status),
        paymentId = paymentId?.let { PaymentId(it) },
        version = Version(version),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ---- SupportSearchQuery --------------------------------------------------
    // open-in-view無効のため参照系は読み取りTxで実行する

    @Transactional(readOnly = true)
    override fun listBySupporter(
        supporterUserId: UserId,
        page: Int,
        size: Int,
    ): PageResult<SupportListItem> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = jpaRepository.findBySupporterUserIdOrderByCreatedAtDesc(supporterUserId.value, pageable)
        // タイトル・決済状態は各コンテキストの公開契約経由で取得する（基本設計 §4.2）
        val titles = projectReferenceQuery.findTitles(result.content.map { it.projectId })
        val paymentStatuses = paymentReferenceQuery.findStatuses(result.content.mapNotNull { it.paymentId })
        return PageResult.of(
            items = result.content.map {
                SupportListItem(
                    supportId = it.supportId,
                    projectId = it.projectId,
                    projectTitle = titles[it.projectId] ?: "",
                    amount = it.supportAmount,
                    status = SupportStatus.valueOf(it.status),
                    paymentStatus = it.paymentId?.let { id -> paymentStatuses[id] },
                    createdAt = it.createdAt,
                )
            },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    @Transactional(readOnly = true)
    override fun findDetail(supportId: SupportId): SupportDetailView? = jpaRepository.findById(supportId.value).orElse(null)?.let { entity ->
        SupportDetailView(
            supportId = entity.supportId,
            projectId = entity.projectId,
            projectTitle = projectReferenceQuery.findTitles(listOf(entity.projectId))[entity.projectId] ?: "",
            supporterUserId = entity.supporterUserId,
            amount = entity.supportAmount,
            status = SupportStatus.valueOf(entity.status),
            paymentStatus = entity.paymentId?.let {
                paymentReferenceQuery.findStatuses(listOf(it))[it]
            },
            contactEmail = entity.contactEmail,
            items = entity.items.map {
                SupportItemView(
                    rewardPlanId = it.rewardPlanId,
                    quantity = it.quantity,
                    unitAmount = it.unitAmount,
                    amount = it.amount,
                )
            },
            version = entity.version,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    // ---- OperationsSupportSearchQuery（運用者横断検索、SCR-060） ---------------

    @Transactional(readOnly = true)
    override fun search(
        status: SupportStatus?,
        projectId: String?,
        page: Int,
        size: Int,
    ): PageResult<OperationsSupportListItem> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = jpaRepository.searchForOperations(status?.name, projectId?.takeIf { it.isNotBlank() }, pageable)
        val titles = projectReferenceQuery.findTitles(result.content.map { it.projectId })
        val paymentStatuses = paymentReferenceQuery.findStatuses(result.content.mapNotNull { it.paymentId })
        return PageResult.of(
            items = result.content.map {
                OperationsSupportListItem(
                    supportId = it.supportId,
                    projectId = it.projectId,
                    projectTitle = titles[it.projectId] ?: "",
                    supporterUserId = it.supporterUserId,
                    amount = it.supportAmount,
                    status = SupportStatus.valueOf(it.status),
                    paymentId = it.paymentId,
                    paymentStatus = it.paymentId?.let { id -> paymentStatuses[id] },
                    createdAt = it.createdAt,
                )
            },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }
}
