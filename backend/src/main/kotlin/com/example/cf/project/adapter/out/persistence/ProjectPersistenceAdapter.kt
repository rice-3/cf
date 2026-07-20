package com.example.cf.project.adapter.out.persistence

import com.example.cf.project.application.query.ProjectDetailView
import com.example.cf.project.application.query.ProjectListItem
import com.example.cf.project.application.query.ProjectReferenceQuery
import com.example.cf.project.application.query.ProjectSearchQuery
import com.example.cf.project.application.query.ProjectSupportabilityView
import com.example.cf.project.application.query.RewardPlanReference
import com.example.cf.project.application.query.RewardPlanView
import com.example.cf.project.domain.model.FundingCondition
import com.example.cf.project.domain.model.FundingType
import com.example.cf.project.domain.model.Project
import com.example.cf.project.domain.model.ProjectBody
import com.example.cf.project.domain.model.ProjectStatus
import com.example.cf.project.domain.model.ProjectSummary
import com.example.cf.project.domain.model.ProjectTitle
import com.example.cf.project.domain.model.RewardPlan
import com.example.cf.project.domain.repository.ProjectRepository
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.id.UserId
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.kernel.time.DateRange
import jakarta.persistence.LockModeType
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository

@Repository
interface ProjectJpaRepository : JpaRepository<ProjectJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProjectJpaEntity p where p.projectId = :id")
    fun findWithLockByProjectId(@Param("id") id: String): ProjectJpaEntity?

    fun findByOwnerUserIdOrderByUpdatedAtDesc(ownerUserId: String): List<ProjectJpaEntity>

    @Query(
        """
        select p from ProjectJpaEntity p
        where p.status = 'PUBLISHED'
          and (p.title like concat('%', :keyword, '%')
               or p.summary like concat('%', :keyword, '%'))
        """,
    )
    fun searchPublished(
        // keywordは常に非null（未指定時は""を渡す）。nullを渡すとHibernateがconcat内のパラメータ型を
        // 推論できずbyteaとみなし、PostgreSQLで「character varying ~~ bytea」エラーになるため避ける。
        @Param("keyword") keyword: String,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<ProjectJpaEntity>

    /** BAT-001 公開開始の対象。多重起動時も同一行を二重処理しない（基本設計 §8.3）。 */
    @Query(
        value = """
            select * from project
             where status = 'APPROVED' and start_at <= :now
             order by start_at
             for update skip locked
             limit :limit
        """,
        nativeQuery = true,
    )
    fun lockPublishTargets(
        @Param("now") now: java.time.Instant,
        @Param("limit") limit: Int,
    ): List<ProjectJpaEntity>

    /** BAT-002 募集終了の対象。 */
    @Query(
        value = """
            select * from project
             where status = 'PUBLISHED' and end_at <= :now
             order by end_at
             for update skip locked
             limit :limit
        """,
        nativeQuery = true,
    )
    fun lockFundingCloseTargets(
        @Param("now") now: java.time.Instant,
        @Param("limit") limit: Int,
    ): List<ProjectJpaEntity>
}

@Repository
interface ProjectStatusHistoryJpaRepository : JpaRepository<ProjectStatusHistoryJpaEntity, String>

@Repository
interface RewardPlanJpaRepository : JpaRepository<RewardPlanJpaEntity, String> {

    /**
     * 数量予約の条件付きUPDATE（詳細設計 §4.3.1）。
     * 在庫上限超過・version競合の場合は更新0件となる。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update RewardPlanJpaEntity r
           set r.reservedQuantity = r.reservedQuantity + :quantity,
               r.version = r.version + 1
         where r.rewardPlanId = :rewardPlanId
           and r.version = :expectedVersion
           and (r.quantityLimit is null or r.reservedQuantity + :quantity <= r.quantityLimit)
        """,
    )
    fun reserveQuantity(
        @Param("rewardPlanId") rewardPlanId: String,
        @Param("quantity") quantity: Int,
        @Param("expectedVersion") expectedVersion: Long,
    ): Int
}

/**
 * Project永続化Adapter。
 * ドメインモデルとJPA Entityの変換をこのクラスへ閉じ込める（ADR-002）。
 */
@Component
class ProjectPersistenceAdapter(
    private val jpaRepository: ProjectJpaRepository,
    private val historyRepository: ProjectStatusHistoryJpaRepository,
    private val rewardPlanRepository: RewardPlanJpaRepository,
    private val idGenerator: UlidGenerator,
) : ProjectRepository, ProjectSearchQuery, ProjectReferenceQuery {

    // ---- ProjectRepository（更新系Port） ------------------------------------

    override fun findById(id: ProjectId): Project? =
        jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByIdForUpdate(id: ProjectId): Project? =
        jpaRepository.findWithLockByProjectId(id.value)?.toDomain()

    override fun findByOwner(ownerUserId: UserId): List<Project> =
        jpaRepository.findByOwnerUserIdOrderByUpdatedAtDesc(ownerUserId.value).map { it.toDomain() }

    override fun lockPublishTargets(now: java.time.Instant, limit: Int): List<Project> =
        jpaRepository.lockPublishTargets(now, limit).map { it.toDomain() }

    override fun lockFundingCloseTargets(now: java.time.Instant, limit: Int): List<Project> =
        jpaRepository.lockFundingCloseTargets(now, limit).map { it.toDomain() }

    override fun save(project: Project) {
        val existing = jpaRepository.findById(project.id.value).orElse(null)
        if (existing == null) {
            val entity = ProjectJpaEntity()
            copyToEntity(project, entity)
            jpaRepository.save(entity)
            recordStatusHistory(project.id.value, null, project.status.name, project.updatedAt)
        } else {
            val previousStatus = existing.status
            copyToEntity(project, existing)
            jpaRepository.save(existing)
            if (previousStatus != project.status.name) {
                // 重要遷移の状態履歴（基本設計 §3.3。実行者・理由はaudit_logに記録される）
                recordStatusHistory(project.id.value, previousStatus, project.status.name, project.updatedAt)
            }
        }
    }

    private fun recordStatusHistory(
        projectId: String,
        from: String?,
        to: String,
        changedAt: java.time.Instant,
    ) {
        historyRepository.save(
            ProjectStatusHistoryJpaEntity(
                historyId = idGenerator.next(),
                projectId = projectId,
                fromStatus = from,
                toStatus = to,
                changedAt = changedAt,
            ),
        )
    }

    private fun copyToEntity(project: Project, entity: ProjectJpaEntity) {
        entity.projectId = project.id.value
        entity.ownerUserId = project.ownerUserId.value
        entity.title = project.title.value
        entity.summary = project.summary.value
        entity.body = project.body.value
        entity.targetAmount = project.fundingCondition.targetAmount.amount
        entity.fundingType = project.fundingCondition.fundingType.name
        entity.startAt = project.fundingCondition.period.start
        entity.endAt = project.fundingCondition.period.end
        entity.status = project.status.name
        entity.mainFileId = project.mainFileId?.value
        entity.version = project.version.value
        entity.createdAt = project.createdAt
        entity.updatedAt = project.updatedAt

        // リターンは全置換（orphanRemoval）。既存IDは維持される。
        entity.rewardPlans.clear()
        project.rewardPlans.forEach { plan ->
            entity.rewardPlans.add(
                RewardPlanJpaEntity(
                    rewardPlanId = plan.id.value,
                    project = entity,
                    name = plan.name,
                    description = plan.description,
                    unitAmount = plan.unitAmount.amount,
                    quantityLimit = plan.quantityLimit,
                    reservedQuantity = plan.reservedQuantity,
                    displayOrder = plan.displayOrder,
                    version = plan.version.value,
                ),
            )
        }
    }

    private fun ProjectJpaEntity.toDomain(): Project = Project(
        id = ProjectId(projectId),
        ownerUserId = UserId(ownerUserId),
        title = ProjectTitle(title),
        summary = ProjectSummary(summary),
        body = ProjectBody(body),
        fundingCondition = FundingCondition(
            targetAmount = Money.of(targetAmount),
            fundingType = FundingType.valueOf(fundingType),
            period = DateRange(startAt, endAt),
        ),
        rewardPlans = rewardPlans.map { it.toDomain() },
        status = ProjectStatus.valueOf(status),
        mainFileId = mainFileId?.let { FileId(it) },
        version = Version(version),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun RewardPlanJpaEntity.toDomain(): RewardPlan = RewardPlan(
        id = RewardPlanId(rewardPlanId),
        name = name,
        description = description,
        unitAmount = Money.of(unitAmount),
        quantityLimit = quantityLimit,
        reservedQuantity = reservedQuantity,
        displayOrder = displayOrder,
        version = Version(version),
    )

    // ---- ProjectSearchQuery（参照系Port） -----------------------------------
    // open-in-view無効のため、LAZYコレクションへ触れる参照系は読み取りTxで実行する

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun searchPublished(keyword: String?, page: Int, size: Int): PageResult<ProjectListItem> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"))
        val result = jpaRepository.searchPublished(keyword?.trim() ?: "", pageable)
        return PageResult.of(
            items = result.content.map { it.toListItem() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun listByOwner(ownerUserId: UserId): List<ProjectListItem> =
        jpaRepository.findByOwnerUserIdOrderByUpdatedAtDesc(ownerUserId.value).map { it.toListItem() }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun findDetail(projectId: ProjectId): ProjectDetailView? =
        jpaRepository.findById(projectId.value).orElse(null)?.let { entity ->
            ProjectDetailView(
                projectId = entity.projectId,
                ownerUserId = entity.ownerUserId,
                title = entity.title,
                summary = entity.summary,
                body = entity.body,
                targetAmount = entity.targetAmount,
                fundingType = FundingType.valueOf(entity.fundingType),
                startAt = entity.startAt,
                endAt = entity.endAt,
                status = ProjectStatus.valueOf(entity.status),
                mainFileId = entity.mainFileId,
                rewardPlans = entity.rewardPlans
                    .sortedBy { it.displayOrder }
                    .map {
                        RewardPlanView(
                            rewardPlanId = it.rewardPlanId,
                            name = it.name,
                            description = it.description,
                            unitAmount = it.unitAmount,
                            quantityLimit = it.quantityLimit,
                            remainingQuantity = it.quantityLimit?.minus(it.reservedQuantity),
                            displayOrder = it.displayOrder,
                        )
                    },
                version = entity.version,
                updatedAt = entity.updatedAt,
            )
        }

    // ---- ProjectReferenceQuery（他コンテキスト公開契約） --------------------

    override fun findTitles(projectIds: Collection<String>): Map<String, String> =
        if (projectIds.isEmpty()) {
            emptyMap()
        } else {
            jpaRepository.findAllById(projectIds).associate { it.projectId to it.title }
        }

    override fun findSupportability(projectId: ProjectId): ProjectSupportabilityView? =
        jpaRepository.findById(projectId.value).orElse(null)?.let {
            ProjectSupportabilityView(
                projectId = it.projectId,
                status = ProjectStatus.valueOf(it.status),
                startAt = it.startAt,
                endAt = it.endAt,
            )
        }

    override fun findRewardPlan(rewardPlanId: String): RewardPlanReference? =
        rewardPlanRepository.findById(rewardPlanId).orElse(null)?.let {
            RewardPlanReference(
                rewardPlanId = it.rewardPlanId,
                projectId = it.project?.projectId ?: "",
                unitAmount = it.unitAmount,
                quantityLimit = it.quantityLimit,
                reservedQuantity = it.reservedQuantity,
                version = it.version,
            )
        }

    override fun reserveRewardQuantity(rewardPlanId: String, quantity: Int, expectedVersion: Long): Boolean =
        rewardPlanRepository.reserveQuantity(rewardPlanId, quantity, expectedVersion) == 1

    private fun ProjectJpaEntity.toListItem() = ProjectListItem(
        projectId = projectId,
        title = title,
        summary = summary,
        targetAmount = targetAmount,
        fundingType = FundingType.valueOf(fundingType),
        startAt = startAt,
        endAt = endAt,
        status = ProjectStatus.valueOf(status),
        updatedAt = updatedAt,
    )
}
