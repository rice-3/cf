package com.example.cf.review.domain.model

import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.AccessDeniedException
import com.example.cf.shared.kernel.error.BusinessRuleViolationException
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.OptimisticLockConflictException
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/** 審査状態（詳細設計 §4.2）。 */
enum class ReviewStatus {
    REQUESTED,
    UNDER_REVIEW,
    APPROVED,
    RETURNED,
    REJECTED,
    WITHDRAWN,
}

/** 却下理由区分。 */
enum class RejectReasonCode {
    LEGAL_VIOLATION,
    INAPPROPRIATE_CONTENT,
    INSUFFICIENT_INFORMATION,
    DUPLICATE_PROJECT,
    OTHER,
}

/**
 * 承認チェックリスト（§5.5 SCR-031）。承認時は必須項目すべてtrueであること。
 */
data class ReviewChecklist(val items: Map<String, Boolean>) {

    fun requireAllConfirmed() {
        val unconfirmed = REQUIRED_ITEMS.filter { items[it] != true }
        if (unconfirmed.isNotEmpty()) {
            throw BusinessRuleViolationException(
                errorCode = "REVIEW_CHECKLIST_INCOMPLETE",
                message = "Review checklist is incomplete",
                violations = unconfirmed,
            )
        }
    }

    companion object {
        val REQUIRED_ITEMS = listOf(
            "CONTENT_CONFIRMED",
            "LEGAL_CONFIRMED",
            "REWARD_CONFIRMED",
            "PERIOD_CONFIRMED",
        )
    }
}

/**
 * ReviewRequest集約ルート（詳細設計 §4.2）。
 */
class ReviewRequest(
    val id: ReviewId,
    val projectId: ProjectId,
    status: ReviewStatus,
    reviewerUserId: UserId?,
    val submittedAt: Instant,
    startedAt: Instant?,
    completedAt: Instant?,
    version: Version,
) {
    var status: ReviewStatus = status
        private set
    var reviewerUserId: UserId? = reviewerUserId
        private set
    var startedAt: Instant? = startedAt
        private set
    var completedAt: Instant? = completedAt
        private set
    var version: Version = version
        private set

    fun requireVersion(expected: Version) {
        if (version != expected) {
            throw OptimisticLockConflictException(
                "Review ${id.value} was updated by another user (expected=${expected.value}, actual=${version.value})",
            )
        }
    }

    /** 担当審査者であることを検証する。 */
    fun requireAssignedTo(userId: UserId) {
        if (reviewerUserId != userId) {
            throw AccessDeniedException(
                errorCode = "REVIEW_NOT_ASSIGNED",
                message = "Review ${id.value} is not assigned to the user",
            )
        }
    }

    /** 審査開始（§4.2 start）。REQUESTED→UNDER_REVIEW。 */
    fun start(reviewer: UserId, now: Instant) {
        if (status != ReviewStatus.REQUESTED) {
            throw InvalidStateException(
                "REVIEW_ALREADY_ASSIGNED",
                "Review ${id.value} cannot start in status $status",
            )
        }
        status = ReviewStatus.UNDER_REVIEW
        reviewerUserId = reviewer
        startedAt = now
        version = version.increment()
    }

    /** 承認（§4.2 approve）。必須チェック完了が事前条件。 */
    fun approve(checklist: ReviewChecklist, now: Instant) {
        requireUnderReview()
        checklist.requireAllConfirmed()
        status = ReviewStatus.APPROVED
        completedAt = now
        version = version.increment()
    }

    /** 差戻し（§4.2 returnForCorrection）。コメント必須。 */
    fun returnForCorrection(comment: String, now: Instant) {
        requireUnderReview()
        if (comment.isBlank()) {
            throw BusinessRuleViolationException(
                errorCode = "REVIEW_COMMENT_REQUIRED",
                message = "Return comment is required",
            )
        }
        status = ReviewStatus.RETURNED
        completedAt = now
        version = version.increment()
    }

    /** 却下（§4.2 reject）。理由区分必須。 */
    fun reject(reasonCode: RejectReasonCode, comment: String, now: Instant) {
        requireUnderReview()
        if (comment.isBlank()) {
            throw BusinessRuleViolationException(
                errorCode = "REVIEW_COMMENT_REQUIRED",
                message = "Reject comment is required",
            )
        }
        status = ReviewStatus.REJECTED
        completedAt = now
        version = version.increment()
    }

    private fun requireUnderReview() {
        if (status != ReviewStatus.UNDER_REVIEW) {
            throw InvalidStateException(
                "REVIEW_INVALID_STATE",
                "Review ${id.value} is not under review: $status",
            )
        }
    }

    companion object {
        /** 審査申請時の生成（UC-PJ-003）。 */
        fun create(id: ReviewId, projectId: ProjectId, submittedAt: Instant): ReviewRequest = ReviewRequest(
            id = id,
            projectId = projectId,
            status = ReviewStatus.REQUESTED,
            reviewerUserId = null,
            submittedAt = submittedAt,
            startedAt = null,
            completedAt = null,
            version = Version(0),
        )
    }
}
