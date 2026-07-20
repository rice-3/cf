package com.example.cf.project.domain.model

import com.example.cf.project.domain.event.ProjectApproved
import com.example.cf.project.domain.event.ProjectCancelled
import com.example.cf.project.domain.event.ProjectCreated
import com.example.cf.project.domain.event.ProjectFailed
import com.example.cf.project.domain.event.ProjectFundingResult
import com.example.cf.project.domain.event.ProjectSucceeded
import com.example.cf.project.domain.event.ProjectPublished
import com.example.cf.project.domain.event.ProjectRejected
import com.example.cf.project.domain.event.ProjectReturned
import com.example.cf.project.domain.event.ProjectReviewStarted
import com.example.cf.project.domain.event.ProjectSubmittedForReview
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.AccessDeniedException
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.OptimisticLockConflictException
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.id.UserId
import com.example.cf.shared.kernel.money.Money
import java.time.Instant

/**
 * Project集約ルート（詳細設計 §4.1）。
 * 状態変更は意味のあるドメインメソッドでのみ行う（§1.4）。
 */
class Project(
    val id: ProjectId,
    val ownerUserId: UserId,
    title: ProjectTitle,
    summary: ProjectSummary,
    body: ProjectBody,
    fundingCondition: FundingCondition,
    rewardPlans: List<RewardPlan>,
    status: ProjectStatus,
    mainFileId: FileId?,
    version: Version,
    val createdAt: Instant,
    updatedAt: Instant,
) {
    var title: ProjectTitle = title
        private set
    var summary: ProjectSummary = summary
        private set
    var body: ProjectBody = body
        private set
    var fundingCondition: FundingCondition = fundingCondition
        private set
    var rewardPlans: List<RewardPlan> = rewardPlans.sortedBy { it.displayOrder }
        private set
    var status: ProjectStatus = status
        private set
    var mainFileId: FileId? = mainFileId
        private set
    var version: Version = version
        private set
    var updatedAt: Instant = updatedAt
        private set

    init {
        require(rewardPlans.size <= MAX_REWARD_PLANS) { "reward plans must be <= $MAX_REWARD_PLANS" }
    }

    // ---- 認可・整合性ガード -------------------------------------------------

    /** 所有者検証（基本設計 §2.3-3: 内部UserIdで判定）。 */
    fun requireOwnedBy(userId: UserId) {
        if (ownerUserId != userId) {
            throw AccessDeniedException(message = "Project ${id.value} is not owned by the user")
        }
    }

    /** 楽観ロック検証（§5.5）。 */
    fun requireVersion(expected: Version) {
        if (version != expected) {
            throw OptimisticLockConflictException(
                "Project ${id.value} was updated by another user (expected=${expected.value}, actual=${version.value})",
            )
        }
    }

    private fun transitionTo(next: ProjectStatus, errorCode: String) {
        if (!status.canTransitionTo(next)) {
            throw InvalidStateException(
                errorCode,
                "Project ${id.value} cannot transition from $status to $next",
            )
        }
        status = next
    }

    private fun touch(now: Instant) {
        version = version.increment()
        updatedAt = now
    }

    // ---- ドメインメソッド（§4.1.2） -----------------------------------------

    /** 下書き内容の更新。DRAFT/RETURNEDのみ可（§4.1.2 updateDraft）。 */
    fun updateDraft(
        title: ProjectTitle,
        summary: ProjectSummary,
        body: ProjectBody,
        fundingCondition: FundingCondition,
        rewardPlans: List<RewardPlan>,
        mainFileId: FileId?,
        now: Instant,
    ) {
        if (!status.editable) {
            throw InvalidStateException(
                "PROJECT_INVALID_STATE",
                "Project ${id.value} is not editable in status $status",
            )
        }
        require(rewardPlans.size <= MAX_REWARD_PLANS) { "reward plans must be <= $MAX_REWARD_PLANS" }
        this.title = title
        this.summary = summary
        this.body = body
        this.fundingCondition = fundingCondition
        this.rewardPlans = rewardPlans.sortedBy { it.displayOrder }
        this.mainFileId = mainFileId
        touch(now)
    }

    /** 審査申請（UC-PJ-003）。事前条件は ProjectSubmissionPolicy で検証済みであること。 */
    fun submitForReview(reviewId: ReviewId, now: Instant): ProjectSubmittedForReview {
        transitionTo(ProjectStatus.REVIEW_REQUESTED, "PROJECT_INVALID_STATE")
        touch(now)
        return ProjectSubmittedForReview(id, reviewId, ownerUserId, now)
    }

    /** 審査開始（UC-RV-001）。 */
    fun startReview(reviewId: ReviewId, reviewerUserId: UserId, now: Instant): ProjectReviewStarted {
        transitionTo(ProjectStatus.UNDER_REVIEW, "PROJECT_INVALID_STATE")
        touch(now)
        return ProjectReviewStarted(id, reviewId, reviewerUserId, now)
    }

    /** 審査承認（UC-RV-002）。 */
    fun approve(reviewId: ReviewId, reviewerUserId: UserId, now: Instant): ProjectApproved {
        transitionTo(ProjectStatus.APPROVED, "PROJECT_INVALID_STATE")
        touch(now)
        return ProjectApproved(id, reviewId, reviewerUserId, now)
    }

    /** 差戻し（UC-RV-003）。コメント必須（§3.3）。 */
    fun returnForCorrection(
        reviewId: ReviewId,
        reviewerUserId: UserId,
        comment: String,
        now: Instant,
    ): ProjectReturned {
        require(comment.isNotBlank()) { "return comment is required" }
        transitionTo(ProjectStatus.RETURNED, "PROJECT_INVALID_STATE")
        touch(now)
        return ProjectReturned(id, reviewId, reviewerUserId, comment, now)
    }

    /** 却下。理由区分必須。 */
    fun reject(reviewId: ReviewId, reviewerUserId: UserId, reasonCode: String, now: Instant): ProjectRejected {
        require(reasonCode.isNotBlank()) { "reject reason code is required" }
        transitionTo(ProjectStatus.REJECTED, "PROJECT_INVALID_STATE")
        touch(now)
        return ProjectRejected(id, reviewId, reviewerUserId, reasonCode, now)
    }

    /** 取消（API-PJ-006）。DRAFT/RETURNED/APPROVED等から可（§4.1.2 cancel）。 */
    fun cancel(actorUserId: UserId, reason: String?, now: Instant): ProjectCancelled {
        transitionTo(ProjectStatus.CANCELLED, "PROJECT_INVALID_STATE")
        touch(now)
        return ProjectCancelled(id, actorUserId, reason, now)
    }

    /** 公開開始（BAT-001）。APPROVEDかつ開始日時到達（§4.1.3）。 */
    fun publish(now: Instant): ProjectPublished {
        if (!fundingCondition.period.isStarted(now)) {
            throw InvalidStateException(
                "PROJECT_INVALID_STATE",
                "Project ${id.value} funding period has not started yet",
            )
        }
        transitionTo(ProjectStatus.PUBLISHED, "PROJECT_INVALID_STATE")
        touch(now)
        return ProjectPublished(id, now)
    }

    /**
     * 募集終了判定（BAT-002）。終了日時到達後に成立/不成立を確定する。
     * 結果に応じてProjectSucceededまたはProjectFailedを発行する（基本設計 §4.6）。
     */
    fun closeFunding(now: Instant, raisedAmount: Money): ProjectFundingResult {
        if (!fundingCondition.period.isEnded(now)) {
            throw InvalidStateException(
                "PROJECT_INVALID_STATE",
                "Project ${id.value} funding period has not ended yet",
            )
        }
        val succeeded = when (fundingCondition.fundingType) {
            FundingType.ALL_OR_NOTHING -> raisedAmount >= fundingCondition.targetAmount
            FundingType.ALL_IN -> raisedAmount.amount > 0
        }
        transitionTo(if (succeeded) ProjectStatus.SUCCEEDED else ProjectStatus.FAILED, "PROJECT_INVALID_STATE")
        touch(now)
        return if (succeeded) {
            ProjectSucceeded(
                projectId = id,
                fundingType = fundingCondition.fundingType,
                targetAmount = fundingCondition.targetAmount.amount,
                raisedAmount = raisedAmount.amount,
                occurredAt = now,
            )
        } else {
            ProjectFailed(
                projectId = id,
                fundingType = fundingCondition.fundingType,
                targetAmount = fundingCondition.targetAmount.amount,
                raisedAmount = raisedAmount.amount,
                occurredAt = now,
            )
        }
    }

    /** 支援受付可能か（§4.1.3: PUBLISHEDかつ募集期間内）。 */
    fun isSupportable(now: Instant): Boolean =
        status == ProjectStatus.PUBLISHED && fundingCondition.period.contains(now)

    companion object {
        const val MAX_REWARD_PLANS = 100

        /** 下書き作成（UC-PJ-001）。 */
        fun create(
            id: ProjectId,
            ownerUserId: UserId,
            title: ProjectTitle,
            summary: ProjectSummary,
            body: ProjectBody,
            fundingCondition: FundingCondition,
            rewardPlans: List<RewardPlan>,
            mainFileId: FileId?,
            now: Instant,
        ): Pair<Project, ProjectCreated> {
            val project = Project(
                id = id,
                ownerUserId = ownerUserId,
                title = title,
                summary = summary,
                body = body,
                fundingCondition = fundingCondition,
                rewardPlans = rewardPlans,
                status = ProjectStatus.DRAFT,
                mainFileId = mainFileId,
                version = Version(0),
                createdAt = now,
                updatedAt = now,
            )
            return project to ProjectCreated(id, ownerUserId, now)
        }
    }
}
