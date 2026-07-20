package com.example.cf.review.adapter.`in`.web

import com.example.cf.review.application.ApproveReviewCommand
import com.example.cf.review.application.ApproveReviewUseCase
import com.example.cf.review.application.RejectReviewCommand
import com.example.cf.review.application.RejectReviewUseCase
import com.example.cf.review.application.ReturnReviewCommand
import com.example.cf.review.application.ReturnReviewUseCase
import com.example.cf.review.application.ReviewActionResult
import com.example.cf.review.application.ReviewDetailView
import com.example.cf.review.application.ReviewListItem
import com.example.cf.review.application.ReviewSearchQuery
import com.example.cf.review.application.StartReviewCommand
import com.example.cf.review.application.StartReviewUseCase
import com.example.cf.review.domain.model.RejectReasonCode
import com.example.cf.review.domain.model.ReviewStatus
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.ReviewId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

// ---- Request / Response DTO -------------------------------------------------

data class ApproveReviewRequest(
    @field:Min(0)
    val expectedVersion: Long = 0,
    val checklist: Map<String, Boolean> = emptyMap(),
    @field:Size(max = 2000)
    val comment: String? = null,
)

data class ReturnReviewRequest(
    @field:Min(0)
    val expectedVersion: Long = 0,
    @field:NotBlank
    @field:Size(min = 1, max = 2000)
    val comment: String = "",
)

data class RejectReviewRequest(
    @field:Min(0)
    val expectedVersion: Long = 0,
    @field:NotBlank
    val reasonCode: String = "",
    @field:NotBlank
    @field:Size(min = 1, max = 2000)
    val comment: String = "",
)

data class ReviewActionResponse(
    val reviewId: String,
    val reviewStatus: String,
    val projectStatus: String,
    val reviewVersion: Long,
    val actedAt: java.time.Instant,
)

private fun ReviewActionResult.toResponse() = ReviewActionResponse(
    reviewId = reviewId.value,
    reviewStatus = reviewStatus.name,
    projectStatus = projectStatus.name,
    reviewVersion = reviewVersion.value,
    actedAt = actedAt,
)

private fun parseReviewId(raw: String): ReviewId =
    if (ULID_PATTERN.matches(raw)) {
        ReviewId(raw)
    } else {
        throw ResourceNotFoundException("REVIEW_NOT_FOUND", "Review $raw is not found")
    }

/**
 * 審査API（API-RV-001〜006、基本設計 §6.4）。
 * ロール検証はSecurity層とUseCase層の双方で行う（基本設計 §2.3）。
 */
@RestController
@RequestMapping("/api/v1/reviews")
class ReviewController(
    private val startReview: StartReviewUseCase,
    private val approveReview: ApproveReviewUseCase,
    private val returnReview: ReturnReviewUseCase,
    private val rejectReview: RejectReviewUseCase,
    private val searchQuery: ReviewSearchQuery,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** API-RV-001 審査一覧（SCR-030）。 */
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<ReviewListItem>> {
        val reviewStatus = status?.let {
            runCatching { ReviewStatus.valueOf(it) }.getOrElse {
                throw ValidationException(message = "status is invalid: $status")
            }
        }
        return searchQuery.search(reviewStatus, page.coerceAtLeast(0), size.coerceIn(1, 100))
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-RV-002 審査詳細（SCR-031）。 */
    @GetMapping("/{reviewId}")
    fun detail(@PathVariable reviewId: String, request: HttpServletRequest): ApiEnvelope<ReviewDetailView> {
        parseReviewId(reviewId)
        val detail = searchQuery.findDetail(reviewId)
            ?: throw ResourceNotFoundException("REVIEW_NOT_FOUND", "Review $reviewId is not found")
        return detail.toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-RV-003 審査開始。 */
    @PostMapping("/{reviewId}/start")
    fun start(@PathVariable reviewId: String, request: HttpServletRequest): ApiEnvelope<ReviewActionResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val result = startReview.execute(
            StartReviewCommand(parseReviewId(reviewId)),
            currentUser,
            userSupport.auditContext(request),
        )
        return result.toResponse().toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-RV-004 承認（詳細設計 §6.6）。 */
    @PostMapping("/{reviewId}/approve")
    fun approve(
        @PathVariable reviewId: String,
        @Valid @RequestBody body: ApproveReviewRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<ReviewActionResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val result = approveReview.execute(
            ApproveReviewCommand(
                reviewId = parseReviewId(reviewId),
                expectedVersion = Version(body.expectedVersion),
                checklist = body.checklist,
                comment = body.comment,
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        return result.toResponse().toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-RV-005 差戻し。コメント必須（基本設計 §5.5）。 */
    @PostMapping("/{reviewId}/return")
    fun returnForCorrection(
        @PathVariable reviewId: String,
        @Valid @RequestBody body: ReturnReviewRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<ReviewActionResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val result = returnReview.execute(
            ReturnReviewCommand(
                reviewId = parseReviewId(reviewId),
                expectedVersion = Version(body.expectedVersion),
                comment = body.comment,
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        return result.toResponse().toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-RV-006 却下。理由区分・コメント必須（基本設計 §5.5）。 */
    @PostMapping("/{reviewId}/reject")
    fun reject(
        @PathVariable reviewId: String,
        @Valid @RequestBody body: RejectReviewRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<ReviewActionResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val reasonCode = runCatching { RejectReasonCode.valueOf(body.reasonCode) }.getOrElse {
            throw ValidationException(message = "reasonCode is invalid: ${body.reasonCode}")
        }
        val result = rejectReview.execute(
            RejectReviewCommand(
                reviewId = parseReviewId(reviewId),
                expectedVersion = Version(body.expectedVersion),
                reasonCode = reasonCode,
                comment = body.comment,
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        return result.toResponse().toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
