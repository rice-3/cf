package com.example.cf.project.application.usecase

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.file.application.FileReferenceQuery
import com.example.cf.project.application.command.CancelProjectCommand
import com.example.cf.project.application.command.CreateProjectCommand
import com.example.cf.project.application.command.SubmitProjectForReviewCommand
import com.example.cf.project.application.command.UpdateProjectCommand
import com.example.cf.project.domain.model.Project
import com.example.cf.project.domain.repository.ProjectRepository
import com.example.cf.project.domain.service.ProjectSubmissionPolicy
import com.example.cf.review.domain.model.ReviewRequest
import com.example.cf.review.domain.repository.ReviewRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.error.BusinessRuleViolationException
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.outbox.OutboxAppendPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val RESOURCE_TYPE = "Project"

private fun ProjectRepository.getForUpdate(id: ProjectId): Project =
    findByIdForUpdate(id)
        ?: throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project ${id.value} is not found")

/**
 * UC-PJ-001 プロジェクト下書き作成（詳細設計 §5.1）。
 */
interface CreateProjectUseCase {
    fun execute(command: CreateProjectCommand, currentUser: CurrentUser, audit: AuditContext): CreateProjectResult
}

@Service
class CreateProjectService(
    private val projectRepository: ProjectRepository,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : CreateProjectUseCase {

    @Transactional
    override fun execute(
        command: CreateProjectCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): CreateProjectResult {
        currentUser.requireRole(RoleCode.OWNER)

        val now = clock.instant()
        val (project, event) = mapDomainValidation {
            Project.create(
                id = ProjectId.newId(idGenerator),
                ownerUserId = currentUser.userId,
                title = buildTitle(command.title),
                summary = buildSummary(command.summary),
                body = buildBody(command.body),
                fundingCondition = buildFundingCondition(
                    command.targetAmount, command.fundingType, command.startAt, command.endAt,
                ),
                rewardPlans = buildRewardPlans(command.rewardPlans, idGenerator),
                mainFileId = command.mainFileId,
                now = now,
            )
        }

        projectRepository.save(project)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "PROJECT_CREATE", RESOURCE_TYPE, project.id.value, "SUCCESS")
        return CreateProjectResult(project.id, project.status)
    }
}

/**
 * UC-PJ-002 プロジェクト下書き更新。
 */
interface UpdateProjectUseCase {
    fun execute(command: UpdateProjectCommand, currentUser: CurrentUser, audit: AuditContext): UpdateProjectResult
}

@Service
class UpdateProjectService(
    private val projectRepository: ProjectRepository,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : UpdateProjectUseCase {

    @Transactional
    override fun execute(
        command: UpdateProjectCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): UpdateProjectResult {
        currentUser.requireRole(RoleCode.OWNER)

        val project = projectRepository.getForUpdate(command.projectId)
        project.requireOwnedBy(currentUser.userId)
        project.requireVersion(command.expectedVersion)

        val now = clock.instant()
        mapDomainValidation {
            project.updateDraft(
                title = buildTitle(command.title),
                summary = buildSummary(command.summary),
                body = buildBody(command.body),
                fundingCondition = buildFundingCondition(
                    command.targetAmount, command.fundingType, command.startAt, command.endAt,
                ),
                rewardPlans = buildRewardPlans(command.rewardPlans, idGenerator),
                mainFileId = command.mainFileId,
                now = now,
            )
        }

        projectRepository.save(project)
        auditPort.record(audit, "PROJECT_UPDATE", RESOURCE_TYPE, project.id.value, "SUCCESS")
        return UpdateProjectResult(project.id, project.status, project.version, project.updatedAt)
    }
}

/**
 * UC-PJ-003 審査申請（詳細設計 §5.2、付録A.1）。
 * Project遷移・ReviewRequest生成・Outbox・Auditを同一トランザクションで保存する。
 */
interface SubmitProjectForReviewUseCase {
    fun execute(
        command: SubmitProjectForReviewCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): SubmitProjectForReviewResult
}

@Service
class SubmitProjectForReviewService(
    private val projectRepository: ProjectRepository,
    private val reviewRepository: ReviewRepository,
    private val submissionPolicy: ProjectSubmissionPolicy,
    private val fileReferenceQuery: FileReferenceQuery,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : SubmitProjectForReviewUseCase {

    @Transactional
    override fun execute(
        command: SubmitProjectForReviewCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): SubmitProjectForReviewResult {
        currentUser.requireRole(RoleCode.OWNER)

        // SCR-023の確認事項がすべて含まれること（API-PJ-005）
        val missing = REQUIRED_CONFIRMATIONS - command.confirmations
        if (missing.isNotEmpty()) {
            throw ValidationException(
                message = "confirmations must contain: ${missing.joinToString(",")}",
            )
        }

        val project = projectRepository.getForUpdate(command.projectId)
        project.requireOwnedBy(currentUser.userId)
        project.requireVersion(command.expectedVersion)

        // 状態不正は409（PROJECT_INVALID_STATE）を先に返す（§6.5。422は完全性不足のみ）
        if (!project.status.editable) {
            throw InvalidStateException(
                "PROJECT_INVALID_STATE",
                "Project ${project.id.value} cannot be submitted in status ${project.status}",
            )
        }

        val now = clock.instant()

        // メイン画像がCOMPLETEかつ本人所有であること（§5.2 検証）
        project.mainFileId?.let { fileId ->
            if (!fileReferenceQuery.isCompletedAndOwnedBy(fileId.value, currentUser.userId.value)) {
                throw BusinessRuleViolationException(
                    errorCode = "PROJECT_INCOMPLETE",
                    message = "Main image is not uploaded completely",
                    violations = listOf("MAIN_IMAGE_NOT_COMPLETED"),
                )
            }
        }
        submissionPolicy.validateOrThrow(project, now)

        // 同一プロジェクトのアクティブ審査が存在しないこと
        reviewRepository.findActiveByProjectId(project.id)?.let {
            throw InvalidStateException(
                "PROJECT_INVALID_STATE",
                "Project ${project.id.value} already has an active review ${it.id.value}",
            )
        }

        val review = ReviewRequest.create(ReviewId.newId(idGenerator), project.id, now)
        val event = project.submitForReview(review.id, now)

        projectRepository.save(project)
        reviewRepository.save(review)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "PROJECT_SUBMIT_REVIEW", RESOURCE_TYPE, project.id.value, "SUCCESS")
        return SubmitProjectForReviewResult(review.id, project.status, now)
    }

    companion object {
        /** 審査申請時に必須の確認事項コード（SCR-023）。 */
        val REQUIRED_CONFIRMATIONS = setOf("TERMS_ACCEPTED", "CONTENT_RESPONSIBILITY_ACCEPTED")
    }
}

/**
 * API-PJ-006 プロジェクト取消。
 */
interface CancelProjectUseCase {
    fun execute(command: CancelProjectCommand, currentUser: CurrentUser, audit: AuditContext): CancelProjectResult
}

@Service
class CancelProjectService(
    private val projectRepository: ProjectRepository,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : CancelProjectUseCase {

    @Transactional
    override fun execute(
        command: CancelProjectCommand,
        currentUser: CurrentUser,
        audit: AuditContext,
    ): CancelProjectResult {
        currentUser.requireRole(RoleCode.OWNER)

        val project = projectRepository.getForUpdate(command.projectId)
        project.requireOwnedBy(currentUser.userId)
        project.requireVersion(command.expectedVersion)

        val event = project.cancel(currentUser.userId, command.reason, clock.instant())

        projectRepository.save(project)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "PROJECT_CANCEL", RESOURCE_TYPE, project.id.value, "SUCCESS")
        return CancelProjectResult(project.id, project.status)
    }
}
