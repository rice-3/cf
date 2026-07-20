package com.example.cf.identity.adapter.`in`.web

import com.example.cf.identity.application.AdminUserService
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.RoleCode
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.id.ULID_PATTERN
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

data class UpdateRolesRequest(
    @field:NotEmpty
    val roles: List<String> = emptyList(),

    @field:Min(0)
    val expectedVersion: Long = 0,

    @field:NotBlank
    @field:Size(min = 1, max = 500)
    val reason: String = "",
)

data class SuspendUserRequest(
    @field:Min(0)
    val expectedVersion: Long = 0,

    @field:Size(max = 500)
    val reason: String? = null,
)

data class AdminUserListItemResponse(
    val userId: String,
    val email: String,
    val displayName: String,
    val status: String,
    val roles: List<String>,
    val version: Long,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)

data class UpdateRolesResponse(val userId: String, val roles: List<String>, val version: Long)

data class SuspendUserResponse(val userId: String, val status: String, val version: Long)

private fun parseUserId(raw: String): String =
    if (ULID_PATTERN.matches(raw)) {
        raw
    } else {
        // ID形式不正は存在しないリソースとして扱い、情報を過剰開示しない（基本設計 §11.1）
        throw ResourceNotFoundException("USER_NOT_FOUND", "User $raw is not found")
    }

/**
 * API-AD-001〜003 会員検索・ロール更新・会員停止（基本設計 §6.6）。
 * URLは `/api/v1/admin` 配下でADMINロールに限定される（SecurityConfig）。
 * UseCase相当のJavaサービスへは、Kotlin/Java境界での明示的変換（§1.3）として
 * CurrentUser/AuditContextを素のString/enum名へ分解して渡す。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController(
    private val adminUserService: AdminUserService,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    /** API-AD-001。 */
    @GetMapping
    fun search(
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): ApiEnvelope<PageResult<AdminUserListItemResponse>> {
        userSupport.requireCurrentUser().requireRole(RoleCode.ADMIN)
        val result = adminUserService.search(keyword, status, page.coerceAtLeast(0), size.coerceIn(1, 100))
        val items = result.items.map {
            AdminUserListItemResponse(
                it.userId(), it.email(), it.displayName(), it.status(), it.roles(),
                it.version(), it.createdAt(), it.updatedAt(),
            )
        }
        return PageResult(items, result.page, result.size, result.totalElements, result.totalPages)
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-AD-002。 */
    @PutMapping("/{userId}/roles")
    fun updateRoles(
        @PathVariable userId: String,
        @Valid @RequestBody body: UpdateRolesRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<UpdateRolesResponse> {
        val currentUser = userSupport.requireCurrentUser()
        currentUser.requireRole(RoleCode.ADMIN)
        val audit = userSupport.auditContext(request)
        val result = adminUserService.updateRoles(
            parseUserId(userId),
            body.roles,
            body.expectedVersion,
            body.reason,
            currentUser.userId.value,
            audit.correlationId.value,
            audit.source.name,
            audit.clientIpHash,
        )
        return UpdateRolesResponse(result.userId(), result.roles(), result.version())
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    /** API-AD-003。 */
    @PostMapping("/{userId}/suspend")
    fun suspend(
        @PathVariable userId: String,
        @Valid @RequestBody body: SuspendUserRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<SuspendUserResponse> {
        val currentUser = userSupport.requireCurrentUser()
        currentUser.requireRole(RoleCode.ADMIN)
        val audit = userSupport.auditContext(request)
        val result = adminUserService.suspend(
            parseUserId(userId),
            body.expectedVersion,
            body.reason,
            currentUser.userId.value,
            audit.correlationId.value,
            audit.source.name,
            audit.clientIpHash,
        )
        return SuspendUserResponse(result.userId(), result.status(), result.version())
            .toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
