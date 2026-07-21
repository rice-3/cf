package com.example.cf.payment.adapter.`in`.web

import com.example.cf.funding.application.OperationsSupportListItem
import com.example.cf.funding.application.OperationsSupportSearchQuery
import com.example.cf.funding.domain.model.SupportStatus
import com.example.cf.payment.application.CreateRefundUseCase
import com.example.cf.payment.application.ReconcilePaymentUseCase
import com.example.cf.payment.application.RefundListItem
import com.example.cf.payment.application.RefundSearchQuery
import com.example.cf.payment.application.RetryRefundUseCase
import com.example.cf.payment.domain.model.RefundReasonCode
import com.example.cf.payment.domain.model.RefundStatus
import com.example.cf.shared.idempotency.IdempotencyOutcome
import com.example.cf.shared.idempotency.IdempotencyPort
import com.example.cf.shared.kernel.IdempotencyKey
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock

/** 冪等スコープ（idempotency_record.scope、§8.21）。 */
private const val REFUND_IDEMPOTENCY_SCOPE = "REFUND_CREATE"

// ---- Request / Response DTO -------------------------------------------------

data class CreateRefundRequest(
    @field:NotBlank
    val reasonCode: String = "",
    @field:Size(max = 2000)
    val comment: String? = null,
    /** 省略時は支援額の全額を返金する（§6.9）。 */
    @field:Min(1)
    val amount: Long? = null,
)

data class RefundResponse(
    val refundId: String,
    val status: String,
)

data class ReconcileResponse(
    val paymentId: String,
    val status: String,
)

private fun parseSupportId(raw: String): SupportId = if (ULID_PATTERN.matches(raw)) {
    SupportId(raw)
} else {
    throw ResourceNotFoundException("SUPPORT_NOT_FOUND", "Support $raw is not found")
}

private fun parseRefundId(raw: String): RefundId = if (ULID_PATTERN.matches(raw)) {
    RefundId(raw)
} else {
    throw ResourceNotFoundException("REFUND_NOT_FOUND", "Refund $raw is not found")
}

private fun parsePaymentId(raw: String): PaymentId = if (ULID_PATTERN.matches(raw)) {
    PaymentId(raw)
} else {
    throw ResourceNotFoundException("PAYMENT_NOT_FOUND", "Payment $raw is not found")
}

/**
 * 運用操作API（API-RF-001 / API-RF-002 / API-PY-002、基本設計 §6.5）。
 *
 * 認可はSecurity層でOPERATOR/ADMINに限定したうえで、UseCase呼出し前にも検証する（§2.3）。
 * 実行者・対象・結果はすべて監査ログへ記録される（§11.5）。
 */
@RestController
@RequestMapping("/api/v1/operations")
class OperationsController(
    private val createRefund: CreateRefundUseCase,
    private val retryRefund: RetryRefundUseCase,
    private val reconcilePayment: ReconcilePaymentUseCase,
    private val supportSearch: OperationsSupportSearchQuery,
    private val refundSearch: RefundSearchQuery,
    private val idempotency: IdempotencyPort,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** API-FD-004 運用者向け支援検索（SCR-060 支援管理）。状態・プロジェクトで横断検索。 */
    @GetMapping("/supports")
    fun searchSupports(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<OperationsSupportListItem>> {
        userSupport.requireCurrentUser().requireRole(RoleCode.OPERATOR)
        val parsedStatus = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { SupportStatus.valueOf(it) }.getOrElse {
                throw ValidationException(message = "status is invalid: $status")
            }
        }
        return supportSearch.search(parsedStatus, projectId, page.coerceAtLeast(0), size.coerceIn(1, 100))
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-RF-003 運用者向け返金検索（SCR-061 返金管理）。状態で再実行対象を絞り込む。 */
    @GetMapping("/refunds")
    fun searchRefunds(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<RefundListItem>> {
        userSupport.requireCurrentUser().requireRole(RoleCode.OPERATOR)
        val parsedStatus = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { RefundStatus.valueOf(it) }.getOrElse {
                throw ValidationException(message = "status is invalid: $status")
            }
        }
        return refundSearch.search(parsedStatus, page.coerceAtLeast(0), size.coerceIn(1, 100))
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-RF-001 返金要求（詳細設計 §6.9）。Idempotency-Key必須。 */
    @PostMapping("/supports/{supportId}/refunds")
    @Transactional
    fun requestRefund(
        @PathVariable supportId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody body: CreateRefundRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ApiEnvelope<RefundResponse>> {
        val currentUser = userSupport.requireCurrentUser()
        currentUser.requireRole(RoleCode.OPERATOR)

        if (idempotencyKey.isNullOrBlank()) {
            throw ValidationException(
                errorCode = "IDEMPOTENCY_KEY_REQUIRED",
                message = "Idempotency-Key header is required",
            )
        }
        val key = runCatching { IdempotencyKey(idempotencyKey) }.getOrElse {
            throw ValidationException(message = "Idempotency-Key is invalid")
        }
        val reasonCode = runCatching { RefundReasonCode.valueOf(body.reasonCode) }.getOrElse {
            throw ValidationException(message = "reasonCode is invalid: ${body.reasonCode}")
        }

        val parsedSupportId = parseSupportId(supportId)
        val outcome = idempotency.begin(
            scope = REFUND_IDEMPOTENCY_SCOPE,
            actorId = currentUser.userId.value,
            key = key,
            requestHash = requestHash(supportId, body),
        )
        if (outcome is IdempotencyOutcome.Replay) {
            return accepted(
                RefundResponse(
                    refundId = outcome.responseBody["refundId"] as String,
                    status = outcome.responseBody["status"] as? String ?: "REQUESTED",
                ),
                request,
            )
        }

        val refundId = createRefund.execute(
            supportId = parsedSupportId,
            reasonCode = reasonCode,
            comment = body.comment,
            amount = body.amount?.let { Money.of(it) },
            audit = userSupport.auditContext(request),
        )
        val response = RefundResponse(refundId.value, "REQUESTED")
        idempotency.complete(
            scope = REFUND_IDEMPOTENCY_SCOPE,
            actorId = currentUser.userId.value,
            key = key,
            responseStatus = 202,
            responseBody = mapOf("refundId" to response.refundId, "status" to response.status),
        )
        return accepted(response, request)
    }

    /** API-RF-002 返金再実行（基本設計 §6.5）。次回のBAT-004で再実行される。 */
    @PostMapping("/refunds/{refundId}/retry")
    fun retry(
        @PathVariable refundId: String,
        request: HttpServletRequest,
    ): ApiEnvelope<RefundResponse> {
        val currentUser = userSupport.requireCurrentUser()
        currentUser.requireRole(RoleCode.OPERATOR)

        val id = parseRefundId(refundId)
        val status = retryRefund.execute(id, userSupport.auditContext(request))
        return RefundResponse(id.value, status.name)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /**
     * API-PY-002 決済照合・再確認（基本設計 §6.5）。
     * 結果不明のままの決済をProviderへ照会し、状態を確定させる（BAT-007と同じ処理）。
     */
    @PostMapping("/payments/{paymentId}/reconcile")
    fun reconcile(
        @PathVariable paymentId: String,
        request: HttpServletRequest,
    ): ApiEnvelope<ReconcileResponse> {
        val currentUser = userSupport.requireCurrentUser()
        currentUser.requireRole(RoleCode.OPERATOR)

        val id = parsePaymentId(paymentId)
        val status = reconcilePayment.execute(id, userSupport.auditContext(request))
        return ReconcileResponse(id.value, status.name)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    private fun accepted(
        response: RefundResponse,
        request: HttpServletRequest,
    ): ResponseEntity<ApiEnvelope<RefundResponse>> = ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(response.toEnvelope(CorrelationIdFilter.from(request), clock.instant()))

    /** 同一キーで内容が異なる要求を検出するためのハッシュ（§8.21 request_hash）。 */
    private fun requestHash(supportId: String, body: CreateRefundRequest): String {
        val canonical = listOf(
            supportId,
            body.reasonCode,
            body.comment ?: "",
            body.amount?.toString() ?: "",
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
