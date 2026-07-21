package com.example.cf.audit.adapter.`in`.web

import com.example.cf.audit.application.AuditSearchQuery
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.error.AccessDeniedException
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val MAX_RANGE_DAYS = 31L

data class AuditLogItemResponse(
    val auditId: String,
    val occurredAt: Instant,
    val actorUserId: String?,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val result: String,
    val correlationId: String,
    val detail: Map<String, Any?>,
)

data class AiActivityLogItemResponse(
    val aiActivityId: String,
    val occurredAt: Instant,
    val actorUserId: String,
    val toolName: String,
    val taskId: String?,
    val actionType: String,
    val repository: String,
    val result: String,
    val approvedBy: String?,
)

/** ADMIN/AUDITORのみ許可（基本設計 §6.6）。URLパターンでは絞れないため明示的に検証する。 */
private fun requireAuditRole(currentUser: com.example.cf.shared.kernel.CurrentUser) {
    if (!currentUser.has(RoleCode.ADMIN) && !currentUser.has(RoleCode.AUDITOR)) {
        throw AccessDeniedException(message = "ADMIN or AUDITOR role is required")
    }
}

private fun requireDateRange(from: Instant?, to: Instant?): Pair<Instant, Instant> {
    if (from == null || to == null) {
        throw ValidationException(message = "from and to are required")
    }
    if (to.isBefore(from)) {
        throw ValidationException(message = "to must not be before from")
    }
    if (Duration.between(from, to) > Duration.ofDays(MAX_RANGE_DAYS)) {
        throw ValidationException(errorCode = "DATE_RANGE_TOO_LARGE", message = "date range must be within $MAX_RANGE_DAYS days")
    }
    return from to to
}

/**
 * API-AU-001/002 監査ログ・AI利用記録検索（基本設計 §6.6、詳細設計 §6.13）。
 */
@RestController
@RequestMapping("/api/v1")
class AuditController(
    private val auditSearchQuery: AuditSearchQuery,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** API-AU-001。 */
    @GetMapping("/audit-logs")
    fun searchAuditLogs(
        @RequestParam from: Instant?,
        @RequestParam to: Instant?,
        @RequestParam(required = false) actorUserId: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) resourceType: String?,
        @RequestParam(required = false) resourceId: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<AuditLogItemResponse>> {
        requireAuditRole(userSupport.requireCurrentUser())
        val (validFrom, validTo) = requireDateRange(from, to)
        val result = auditSearchQuery.searchAuditLogs(
            validFrom,
            validTo,
            actorUserId,
            action,
            resourceType,
            resourceId,
            page.coerceAtLeast(0),
            size.coerceIn(1, 100),
        )
        val items = result.items.map {
            AuditLogItemResponse(
                it.auditId(), it.occurredAt(), it.actorUserId(), it.action(), it.resourceType(),
                it.resourceId(), it.result(), it.correlationId(), it.detail(),
            )
        }
        return PageResult(items, result.page, result.size, result.totalElements, result.totalPages)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-AU-002。 */
    @GetMapping("/ai-activities")
    fun searchAiActivities(
        @RequestParam from: Instant?,
        @RequestParam to: Instant?,
        @RequestParam(required = false) actorUserId: String?,
        @RequestParam(required = false) toolName: String?,
        @RequestParam(required = false) actionType: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<AiActivityLogItemResponse>> {
        requireAuditRole(userSupport.requireCurrentUser())
        val (validFrom, validTo) = requireDateRange(from, to)
        val result = auditSearchQuery.searchAiActivities(
            validFrom,
            validTo,
            actorUserId,
            toolName,
            actionType,
            page.coerceAtLeast(0),
            size.coerceIn(1, 100),
        )
        val items = result.items.map {
            AiActivityLogItemResponse(
                it.aiActivityId(), it.occurredAt(), it.actorUserId(), it.toolName(), it.taskId(),
                it.actionType(), it.repository(), it.result(), it.approvedBy(),
            )
        }
        return PageResult(items, result.page, result.size, result.totalElements, result.totalPages)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
