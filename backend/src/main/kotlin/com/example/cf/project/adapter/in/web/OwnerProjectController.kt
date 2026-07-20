package com.example.cf.project.adapter.`in`.web

import com.example.cf.project.application.command.CancelProjectCommand
import com.example.cf.project.application.command.CreateProjectCommand
import com.example.cf.project.application.command.SubmitProjectForReviewCommand
import com.example.cf.project.application.command.UpdateProjectCommand
import com.example.cf.project.application.query.ProjectListItem
import com.example.cf.project.application.query.ProjectSearchQuery
import com.example.cf.project.application.usecase.CancelProjectUseCase
import com.example.cf.project.application.usecase.CreateProjectUseCase
import com.example.cf.project.application.usecase.SubmitProjectForReviewUseCase
import com.example.cf.project.application.usecase.UpdateProjectUseCase
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Clock

internal fun parseProjectId(raw: String): ProjectId =
    if (ULID_PATTERN.matches(raw)) {
        ProjectId(raw)
    } else {
        // ID形式不正は存在しないリソースとして扱い、情報を過剰開示しない（§11.1）
        throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project $raw is not found")
    }

/**
 * 起案者向けプロジェクトAPI（API-PJ-003〜006、基本設計 §6.4）。
 * ControllerはHTTP変換と入力検証に限定する（§6.7）。
 */
@RestController
@RequestMapping("/api/v1/owner/projects")
class OwnerProjectController(
    private val createProject: CreateProjectUseCase,
    private val updateProject: UpdateProjectUseCase,
    private val submitForReview: SubmitProjectForReviewUseCase,
    private val cancelProject: CancelProjectUseCase,
    private val searchQuery: ProjectSearchQuery,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** SCR-020 起案者プロジェクト一覧。 */
    @GetMapping
    fun listOwn(request: HttpServletRequest): ApiEnvelope<List<ProjectListItem>> {
        val currentUser = userSupport.requireCurrentUser()
        return searchQuery.listByOwner(currentUser.userId)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-PJ-003 下書き作成。 */
    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateProjectRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ApiEnvelope<ProjectCreatedResponse>> {
        val currentUser = userSupport.requireCurrentUser()
        val result = createProject.execute(
            CreateProjectCommand(
                title = body.title,
                summary = body.summary,
                body = body.body,
                targetAmount = body.targetAmount,
                fundingType = body.fundingType,
                startAt = body.startAt,
                endAt = body.endAt,
                mainFileId = body.mainFileId?.let { FileId(it) },
                rewardPlans = body.rewardPlans.map { it.toCommand() },
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        val response = ProjectCreatedResponse(result.projectId.value, result.status.name)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
        return ResponseEntity
            .created(URI.create("/api/v1/owner/projects/${result.projectId.value}"))
            .body(response)
    }

    /** API-PJ-004 下書き更新。 */
    @PutMapping("/{projectId}")
    fun update(
        @PathVariable projectId: String,
        @Valid @RequestBody body: UpdateProjectRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<ProjectUpdatedResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val result = updateProject.execute(
            UpdateProjectCommand(
                projectId = parseProjectId(projectId),
                expectedVersion = Version(body.expectedVersion),
                title = body.title,
                summary = body.summary,
                body = body.body,
                targetAmount = body.targetAmount,
                fundingType = body.fundingType,
                startAt = body.startAt,
                endAt = body.endAt,
                mainFileId = body.mainFileId?.let { FileId(it) },
                rewardPlans = body.rewardPlans.map { it.toCommand() },
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        return ProjectUpdatedResponse(
            projectId = result.projectId.value,
            status = result.status.name,
            version = result.version.value,
            updatedAt = result.updatedAt,
        ).toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-PJ-005 審査申請。202 Accepted（詳細設計 §5.2）。 */
    @PostMapping("/{projectId}/review-requests")
    fun submitReview(
        @PathVariable projectId: String,
        @Valid @RequestBody body: SubmitReviewRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ApiEnvelope<SubmitReviewResponse>> {
        val currentUser = userSupport.requireCurrentUser()
        val result = submitForReview.execute(
            SubmitProjectForReviewCommand(
                projectId = parseProjectId(projectId),
                expectedVersion = Version(body.expectedVersion),
                confirmations = body.confirmations.toSet(),
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        val response = SubmitReviewResponse(
            reviewId = result.reviewId.value,
            projectStatus = result.projectStatus.name,
            submittedAt = result.submittedAt,
        ).toEnvelope(CorrelationIdFilter.from(request), clock.instant())
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response)
    }

    /** API-PJ-006 取消。 */
    @PostMapping("/{projectId}/cancel")
    fun cancel(
        @PathVariable projectId: String,
        @Valid @RequestBody body: CancelProjectRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<ProjectCancelledResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val result = cancelProject.execute(
            CancelProjectCommand(
                projectId = parseProjectId(projectId),
                expectedVersion = Version(body.expectedVersion),
                reason = body.reason,
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        return ProjectCancelledResponse(result.projectId.value, result.status.name)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
