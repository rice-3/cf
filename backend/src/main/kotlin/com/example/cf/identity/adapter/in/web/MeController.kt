package com.example.cf.identity.adapter.`in`.web

import com.example.cf.identity.application.ProfileService
import com.example.cf.shared.web.ApiEnvelope
import com.example.cf.shared.web.CorrelationIdFilter
import com.example.cf.shared.web.CurrentUserSupport
import com.example.cf.shared.web.toEnvelope
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

data class UpdateProfileRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 100)
    val displayName: String = "",

    @field:NotBlank
    @field:Email
    val email: String = "",

    @field:Min(0)
    val expectedVersion: Long = 0,
)

data class ProfileResponse(
    val userId: String,
    val email: String,
    val displayName: String,
    val status: String,
    val roles: List<String>,
    val version: Long,
    val updatedAt: java.time.Instant,
)

/**
 * API-US-001/002 プロフィール取得・更新（基本設計 §6.6、詳細設計 §6.14）。
 * 認可は「認証済」のみ（自分自身の情報のみ扱うため、URLに他者IDを含めない）。
 */
@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val profileService: ProfileService,
    private val userSupport: CurrentUserSupport,
    private val clock: Clock,
) {

    @GetMapping
    fun getProfile(request: HttpServletRequest): ApiEnvelope<ProfileResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val profile = profileService.getProfile(currentUser.userId.value)
        return ProfileResponse(
            userId = profile.userId(),
            email = profile.email(),
            displayName = profile.displayName(),
            status = profile.status(),
            roles = profile.roles(),
            version = profile.version(),
            updatedAt = profile.updatedAt(),
        ).toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }

    @PutMapping
    fun updateProfile(
        @Valid @RequestBody body: UpdateProfileRequest,
        request: HttpServletRequest,
    ): ApiEnvelope<ProfileResponse> {
        val currentUser = userSupport.requireCurrentUser()
        val audit = userSupport.auditContext(request)
        val result = profileService.updateProfile(
            currentUser.userId.value,
            body.displayName,
            body.email,
            body.expectedVersion,
            audit.correlationId.value,
            audit.source.name,
            audit.clientIpHash,
        )
        // 更新後の最新プロフィール（ロール込み）を返す
        val profile = profileService.getProfile(result.userId())
        return ProfileResponse(
            userId = profile.userId(),
            email = profile.email(),
            displayName = profile.displayName(),
            status = profile.status(),
            roles = profile.roles(),
            version = profile.version(),
            updatedAt = profile.updatedAt(),
        ).toEnvelope(CorrelationIdFilter.from(request), clock.instant())
    }
}
