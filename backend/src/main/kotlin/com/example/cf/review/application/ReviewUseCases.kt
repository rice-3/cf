package com.example.cf.review.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.project.domain.repository.ProjectRepository
import com.example.cf.review.domain.model.RejectReasonCode
import com.example.cf.review.domain.model.ReviewChecklist
import com.example.cf.review.domain.model.ReviewRequest
import com.example.cf.review.domain.model.ReviewStatus
import com.example.cf.review.domain.repository.ReviewRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.id.UserId
import com.example.cf.shared.outbox.OutboxAppendPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

private const val RESOURCE_TYPE = "Review"

/** 審査操作の共通結果。 */
data class ReviewActionResult(
    val reviewId: ReviewId,
    val reviewStatus: ReviewStatus,
    val projectStatus: com.example.cf.project.domain.model.ProjectStatus,
    val reviewVersion: Version,
    val actedAt: Instant,
)

// ---- コマンド ---------------------------------------------------------------

data class StartReviewCommand(val reviewId: ReviewId)

data class ApproveReviewCommand(
    val reviewId: ReviewId,
    val expectedVersion: Version,
    val checklist: Map<String, Boolean>,
    val comment: String?,
)

data class ReturnReviewCommand(
    val reviewId: ReviewId,
    val expectedVersion: Version,
    val comment: String,
)

data class RejectReviewCommand(
    val reviewId: ReviewId,
    val expectedVersion: Version,
    val reasonCode: RejectReasonCode,
    val comment: String,
)

// ---- 審査履歴Port（review_history、詳細設計 §8.9） -------------------------

interface ReviewHistoryPort {
    fun record(
        reviewId: ReviewId,
        action: String,
        reasonCode: String?,
        comment: String?,
        checklist: Map<String, Boolean>?,
        actedBy: UserId,
        actedAt: Instant,
    )
}

// ---- UseCase実装 ------------------------------------------------------------

private fun ReviewRepository.getForUpdate(id: ReviewId): ReviewRequest = findByIdForUpdate(id)
    ?: throw ResourceNotFoundException("REVIEW_NOT_FOUND", "Review ${id.value} is not found")

/**
 * UC-RV-001 審査開始。Review割当とProject状態遷移を同一トランザクションで行う。
 */
interface StartReviewUseCase {
    fun execute(command: StartReviewCommand, currentUser: CurrentUser, audit: AuditContext): ReviewActionResult
}

@Service
class StartReviewService(
    private val reviewRepository: ReviewRepository,
    private val projectRepository: ProjectRepository,
    private val historyPort: ReviewHistoryPort,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : StartReviewUseCase {

    @Transactional
    override fun execute(
        command: StartReviewCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): ReviewActionResult {
        currentUser.requireRole(RoleCode.REVIEWER)

        val review = reviewRepository.getForUpdate(command.reviewId)
        val project = projectRepository.findByIdForUpdate(review.projectId)
            ?: throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project ${review.projectId.value} is not found")

        val now = clock.instant()
        review.start(currentUser.userId, now)
        val event = project.startReview(review.id, currentUser.userId, now)

        reviewRepository.save(review)
        projectRepository.save(project)
        historyPort.record(review.id, "START", null, null, null, currentUser.userId, now)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "REVIEW_START", RESOURCE_TYPE, review.id.value, "SUCCESS")
        return ReviewActionResult(review.id, review.status, project.status, review.version, now)
    }
}

/**
 * UC-RV-002 審査承認（API-RV-004）。
 */
interface ApproveReviewUseCase {
    fun execute(command: ApproveReviewCommand, currentUser: CurrentUser, audit: AuditContext): ReviewActionResult
}

@Service
class ApproveReviewService(
    private val reviewRepository: ReviewRepository,
    private val projectRepository: ProjectRepository,
    private val historyPort: ReviewHistoryPort,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : ApproveReviewUseCase {

    @Transactional
    override fun execute(
        command: ApproveReviewCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): ReviewActionResult {
        currentUser.requireRole(RoleCode.REVIEWER)

        val review = reviewRepository.getForUpdate(command.reviewId)
        review.requireAssignedTo(currentUser.userId)
        review.requireVersion(command.expectedVersion)

        val project = projectRepository.findByIdForUpdate(review.projectId)
            ?: throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project ${review.projectId.value} is not found")

        val now = clock.instant()
        val checklist = ReviewChecklist(command.checklist)
        review.approve(checklist, now)
        val event = project.approve(review.id, currentUser.userId, now)

        reviewRepository.save(review)
        projectRepository.save(project)
        historyPort.record(
            review.id,
            "APPROVE",
            null,
            command.comment,
            command.checklist,
            currentUser.userId,
            now,
        )
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "REVIEW_APPROVE", RESOURCE_TYPE, review.id.value, "SUCCESS")
        return ReviewActionResult(review.id, review.status, project.status, review.version, now)
    }
}

/**
 * UC-RV-003 審査差戻し（API-RV-005）。
 */
interface ReturnReviewUseCase {
    fun execute(command: ReturnReviewCommand, currentUser: CurrentUser, audit: AuditContext): ReviewActionResult
}

@Service
class ReturnReviewService(
    private val reviewRepository: ReviewRepository,
    private val projectRepository: ProjectRepository,
    private val historyPort: ReviewHistoryPort,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : ReturnReviewUseCase {

    @Transactional
    override fun execute(
        command: ReturnReviewCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): ReviewActionResult {
        currentUser.requireRole(RoleCode.REVIEWER)

        val review = reviewRepository.getForUpdate(command.reviewId)
        review.requireAssignedTo(currentUser.userId)
        review.requireVersion(command.expectedVersion)

        val project = projectRepository.findByIdForUpdate(review.projectId)
            ?: throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project ${review.projectId.value} is not found")

        val now = clock.instant()
        review.returnForCorrection(command.comment, now)
        val event = project.returnForCorrection(review.id, currentUser.userId, command.comment, now)

        reviewRepository.save(review)
        projectRepository.save(project)
        historyPort.record(review.id, "RETURN", null, command.comment, null, currentUser.userId, now)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "REVIEW_RETURN", RESOURCE_TYPE, review.id.value, "SUCCESS")
        return ReviewActionResult(review.id, review.status, project.status, review.version, now)
    }
}

/**
 * 審査却下（API-RV-006）。
 */
interface RejectReviewUseCase {
    fun execute(command: RejectReviewCommand, currentUser: CurrentUser, audit: AuditContext): ReviewActionResult
}

@Service
class RejectReviewService(
    private val reviewRepository: ReviewRepository,
    private val projectRepository: ProjectRepository,
    private val historyPort: ReviewHistoryPort,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : RejectReviewUseCase {

    @Transactional
    override fun execute(
        command: RejectReviewCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): ReviewActionResult {
        currentUser.requireRole(RoleCode.REVIEWER)

        val review = reviewRepository.getForUpdate(command.reviewId)
        review.requireAssignedTo(currentUser.userId)
        review.requireVersion(command.expectedVersion)

        val project = projectRepository.findByIdForUpdate(review.projectId)
            ?: throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project ${review.projectId.value} is not found")

        val now = clock.instant()
        review.reject(command.reasonCode, command.comment, now)
        val event = project.reject(review.id, currentUser.userId, command.reasonCode.name, now)

        reviewRepository.save(review)
        projectRepository.save(project)
        historyPort.record(
            review.id,
            "REJECT",
            command.reasonCode.name,
            command.comment,
            null,
            currentUser.userId,
            now,
        )
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "REVIEW_REJECT", RESOURCE_TYPE, review.id.value, "SUCCESS")
        return ReviewActionResult(review.id, review.status, project.status, review.version, now)
    }
}
