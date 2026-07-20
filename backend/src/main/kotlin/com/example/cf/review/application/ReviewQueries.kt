package com.example.cf.review.application

import com.example.cf.review.domain.model.ReviewStatus
import com.example.cf.shared.kernel.PageResult
import java.time.Instant

/** 審査一覧Read Model（API-RV-001、SCR-030）。 */
data class ReviewListItem(
    val reviewId: String,
    val projectId: String,
    val projectTitle: String,
    val status: ReviewStatus,
    val reviewerUserId: String?,
    val submittedAt: Instant,
)

/** 審査詳細Read Model（API-RV-002、SCR-031）。 */
data class ReviewDetailView(
    val reviewId: String,
    val projectId: String,
    val status: ReviewStatus,
    val reviewerUserId: String?,
    val submittedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val version: Long,
    val histories: List<ReviewHistoryView>,
)

data class ReviewHistoryView(
    val action: String,
    val reasonCode: String?,
    val comment: String?,
    val actedAt: Instant,
    val actedBy: String,
)

/** 参照系Query Port。実装は adapter.out.persistence に置く。 */
interface ReviewSearchQuery {
    fun search(status: ReviewStatus?, page: Int, size: Int): PageResult<ReviewListItem>

    fun findDetail(reviewId: String): ReviewDetailView?
}
