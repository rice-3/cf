package com.example.cf.funding.adapter.`in`.web

import com.example.cf.funding.application.CancelSupportCommand
import com.example.cf.funding.application.CancelSupportUseCase
import com.example.cf.funding.application.RequestSupportCommand
import com.example.cf.funding.application.RequestSupportUseCase
import com.example.cf.funding.application.SupportDetailView
import com.example.cf.funding.application.SupportListItem
import com.example.cf.funding.application.SupportSearchQuery
import com.example.cf.shared.kernel.IdempotencyKey
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

// ---- Request / Response DTO -------------------------------------------------

data class RequestSupportBody(
    val rewardPlanId: String? = null,
    @field:Min(1)
    @field:Max(99)
    val quantity: Int = 1,
    @field:Min(0)
    val additionalAmount: Long = 0,
    @field:NotBlank
    @field:Email
    val contactEmail: String = "",
    val termsAccepted: Boolean = false,
)

data class RequestSupportResponse(
    val supportId: String,
    val paymentStatus: String,
    val statusUrl: String,
)

data class CancelSupportResponse(
    val supportId: String,
    val status: String,
)

private fun parseSupportId(raw: String): SupportId =
    if (ULID_PATTERN.matches(raw)) {
        SupportId(raw)
    } else {
        throw ResourceNotFoundException("SUPPORT_NOT_FOUND", "Support $raw is not found")
    }

private fun parseProjectId(raw: String): ProjectId =
    if (ULID_PATTERN.matches(raw)) {
        ProjectId(raw)
    } else {
        throw ResourceNotFoundException("PROJECT_NOT_FOUND", "Project $raw is not found")
    }

/**
 * 支援API（API-FD-001〜004、基本設計 §6.5）。
 */
@RestController
class SupportController(
    private val requestSupport: RequestSupportUseCase,
    private val cancelSupport: CancelSupportUseCase,
    private val searchQuery: SupportSearchQuery,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** API-FD-001 支援申込（詳細設計 §6.7）。Idempotency-Key必須。 */
    @PostMapping("/api/v1/projects/{projectId}/supports")
    fun request(
        @PathVariable projectId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody body: RequestSupportBody,
        request: HttpServletRequest,
    ): ResponseEntity<ApiEnvelope<RequestSupportResponse>> {
        val currentUser = userSupport.requireCurrentUser()
        if (idempotencyKey.isNullOrBlank()) {
            throw ValidationException(
                errorCode = "IDEMPOTENCY_KEY_REQUIRED",
                message = "Idempotency-Key header is required",
            )
        }
        val key = runCatching { IdempotencyKey(idempotencyKey) }.getOrElse {
            throw ValidationException(message = "Idempotency-Key is invalid")
        }
        val rewardPlanId = body.rewardPlanId?.takeIf { it.isNotBlank() }?.let {
            if (!ULID_PATTERN.matches(it)) {
                throw ValidationException(message = "rewardPlanId is invalid")
            }
            RewardPlanId(it)
        }

        val result = requestSupport.execute(
            RequestSupportCommand(
                projectId = parseProjectId(projectId),
                rewardPlanId = rewardPlanId,
                quantity = body.quantity,
                additionalAmount = body.additionalAmount,
                contactEmail = body.contactEmail,
                termsAccepted = body.termsAccepted,
                idempotencyKey = key,
            ),
            currentUser,
            userSupport.auditContext(request),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            RequestSupportResponse(
                supportId = result.supportId.value,
                paymentStatus = result.paymentStatus,
                statusUrl = result.statusUrl,
            ).toEnvelope(CorrelationIdFilter.from(request), clock.instant()),
        )
    }

    /** API-FD-002 自分の支援一覧（SCR-051）。 */
    @GetMapping("/api/v1/me/supports")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<SupportListItem>> {
        val currentUser = userSupport.requireCurrentUser()
        return searchQuery.listBySupporter(currentUser.userId, page.coerceAtLeast(0), size.coerceIn(1, 100))
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-FD-003 支援詳細。他者の支援は404で秘匿する。 */
    @GetMapping("/api/v1/me/supports/{supportId}")
    fun detail(
        @PathVariable supportId: String,
        request: HttpServletRequest,
    ): ApiEnvelope<SupportDetailView> {
        val currentUser = userSupport.requireCurrentUser()
        val detail = searchQuery.findDetail(parseSupportId(supportId))
        if (detail == null || detail.supporterUserId != currentUser.userId.value) {
            throw ResourceNotFoundException("SUPPORT_NOT_FOUND", "Support $supportId is not found")
        }
        return detail.toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-FD-004 支援取消要求。 */
    @PostMapping("/api/v1/me/supports/{supportId}/cancel")
    fun cancel(
        @PathVariable supportId: String,
        request: HttpServletRequest,
    ): ApiEnvelope<CancelSupportResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val result = cancelSupport.execute(
            CancelSupportCommand(parseSupportId(supportId)),
            currentUser,
            userSupport.auditContext(request),
        )
        return CancelSupportResponse(
            supportId = result.supportId.value,
            status = result.status.name,
        ).toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
