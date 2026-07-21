package com.example.cf.review.adapter.out.persistence

import com.example.cf.project.application.query.ProjectReferenceQuery
import com.example.cf.review.application.ReviewDetailView
import com.example.cf.review.application.ReviewHistoryPort
import com.example.cf.review.application.ReviewHistoryView
import com.example.cf.review.application.ReviewListItem
import com.example.cf.review.application.ReviewSearchQuery
import com.example.cf.review.domain.model.ReviewRequest
import com.example.cf.review.domain.model.ReviewStatus
import com.example.cf.review.domain.repository.ReviewRepository
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.id.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Instant

/** review_request テーブル（詳細設計 §8.8）。 */
@Entity
@Table(name = "review_request")
class ReviewJpaEntity(
    @Id
    @Column(name = "review_id", length = 26)
    var reviewId: String = "",

    @Column(name = "project_id", length = 26, nullable = false)
    var projectId: String = "",

    @Column(name = "status", length = 30, nullable = false)
    var status: String = "",

    @Column(name = "reviewer_user_id", length = 26)
    var reviewerUserId: String? = null,

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: Instant = Instant.EPOCH,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

/** review_history テーブル（詳細設計 §8.9）。 */
@Entity
@Table(name = "review_history")
class ReviewHistoryJpaEntity(
    @Id
    @Column(name = "review_history_id", length = 26)
    var reviewHistoryId: String = "",

    @Column(name = "review_id", length = 26, nullable = false)
    var reviewId: String = "",

    @Column(name = "action", length = 30, nullable = false)
    var action: String = "",

    @Column(name = "reason_code", length = 50)
    var reasonCode: String? = null,

    @Column(name = "comment", length = 2000)
    var comment: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checklist_json")
    var checklistJson: Map<String, Boolean>? = null,

    @Column(name = "acted_at", nullable = false)
    var actedAt: Instant = Instant.EPOCH,

    @Column(name = "acted_by", length = 26, nullable = false)
    var actedBy: String = "",
)

@Repository
interface ReviewJpaRepository : JpaRepository<ReviewJpaEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReviewJpaEntity r where r.reviewId = :id")
    fun findWithLockByReviewId(@Param("id") id: String): ReviewJpaEntity?

    @Query("select r from ReviewJpaEntity r where r.projectId = :projectId and r.status in ('REQUESTED', 'UNDER_REVIEW')")
    fun findActiveByProjectId(@Param("projectId") projectId: String): ReviewJpaEntity?

    fun findByStatus(status: String): List<ReviewJpaEntity>

    fun findByStatusOrderBySubmittedAtAsc(
        status: String,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<ReviewJpaEntity>
}

@Repository
interface ReviewHistoryJpaRepository : JpaRepository<ReviewHistoryJpaEntity, String> {
    fun findByReviewIdOrderByActedAtDesc(reviewId: String): List<ReviewHistoryJpaEntity>
}

/**
 * Review永続化Adapter（Repository / History / Query Port実装）。
 */
@Component
class ReviewPersistenceAdapter(
    private val jpaRepository: ReviewJpaRepository,
    private val historyRepository: ReviewHistoryJpaRepository,
    private val projectReferenceQuery: ProjectReferenceQuery,
    private val idGenerator: UlidGenerator,
) : ReviewRepository,
    ReviewHistoryPort,
    ReviewSearchQuery {

    // ---- ReviewRepository ----------------------------------------------------

    override fun findById(id: ReviewId): ReviewRequest? = jpaRepository.findById(id.value).orElse(null)?.toDomain()

    override fun findByIdForUpdate(id: ReviewId): ReviewRequest? = jpaRepository.findWithLockByReviewId(id.value)?.toDomain()

    override fun findActiveByProjectId(projectId: ProjectId): ReviewRequest? = jpaRepository.findActiveByProjectId(projectId.value)?.toDomain()

    override fun findByStatus(status: ReviewStatus): List<ReviewRequest> = jpaRepository.findByStatus(status.name).map { it.toDomain() }

    override fun save(review: ReviewRequest) {
        val entity = jpaRepository.findById(review.id.value).orElse(ReviewJpaEntity())
        entity.reviewId = review.id.value
        entity.projectId = review.projectId.value
        entity.status = review.status.name
        entity.reviewerUserId = review.reviewerUserId?.value
        entity.submittedAt = review.submittedAt
        entity.startedAt = review.startedAt
        entity.completedAt = review.completedAt
        entity.version = review.version.value
        jpaRepository.save(entity)
    }

    private fun ReviewJpaEntity.toDomain() = ReviewRequest(
        id = ReviewId(reviewId),
        projectId = ProjectId(projectId),
        status = ReviewStatus.valueOf(status),
        reviewerUserId = reviewerUserId?.let { UserId(it) },
        submittedAt = submittedAt,
        startedAt = startedAt,
        completedAt = completedAt,
        version = Version(version),
    )

    // ---- ReviewHistoryPort ---------------------------------------------------

    override fun record(
        reviewId: ReviewId,
        action: String,
        reasonCode: String?,
        comment: String?,
        checklist: Map<String, Boolean>?,
        actedBy: UserId,
        actedAt: Instant,
    ) {
        historyRepository.save(
            ReviewHistoryJpaEntity(
                reviewHistoryId = idGenerator.next(),
                reviewId = reviewId.value,
                action = action,
                reasonCode = reasonCode,
                comment = comment,
                checklistJson = checklist,
                actedAt = actedAt,
                actedBy = actedBy.value,
            ),
        )
    }

    // ---- ReviewSearchQuery ---------------------------------------------------

    override fun search(status: ReviewStatus?, page: Int, size: Int): PageResult<ReviewListItem> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "submittedAt"))
        val result = jpaRepository.findByStatusOrderBySubmittedAtAsc(
            (status ?: ReviewStatus.REQUESTED).name,
            pageable,
        )
        // タイトルはProject公開契約経由で取得（テーブル直接JOIN禁止、基本設計 §4.2）
        val titles = projectReferenceQuery.findTitles(result.content.map { it.projectId })
        return PageResult.of(
            items = result.content.map {
                ReviewListItem(
                    reviewId = it.reviewId,
                    projectId = it.projectId,
                    projectTitle = titles[it.projectId] ?: "",
                    status = ReviewStatus.valueOf(it.status),
                    reviewerUserId = it.reviewerUserId,
                    submittedAt = it.submittedAt,
                )
            },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    override fun findDetail(reviewId: String): ReviewDetailView? = jpaRepository.findById(reviewId).orElse(null)?.let { entity ->
        ReviewDetailView(
            reviewId = entity.reviewId,
            projectId = entity.projectId,
            status = ReviewStatus.valueOf(entity.status),
            reviewerUserId = entity.reviewerUserId,
            submittedAt = entity.submittedAt,
            startedAt = entity.startedAt,
            completedAt = entity.completedAt,
            version = entity.version,
            histories = historyRepository.findByReviewIdOrderByActedAtDesc(entity.reviewId).map {
                ReviewHistoryView(
                    action = it.action,
                    reasonCode = it.reasonCode,
                    comment = it.comment,
                    actedAt = it.actedAt,
                    actedBy = it.actedBy,
                )
            },
        )
    }
}
