package com.example.cf.shared.web

import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.AuditSource
import com.example.cf.shared.kernel.CurrentUser
import com.example.cf.shared.kernel.error.AuthenticationRequiredException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * SecurityContextからCurrentUser・AuditContextを解決する（詳細設計 §5.1-1）。
 */
@Component
class CurrentUserSupport {

    /** 認証必須APIで使用する。未認証は401。 */
    fun requireCurrentUser(): CurrentUser =
        findCurrentUser() ?: throw AuthenticationRequiredException()

    /** 公開APIで任意認証として使用する。 */
    fun findCurrentUser(): CurrentUser? {
        val authentication = SecurityContextHolder.getContext().authentication ?: return null
        return authentication.principal as? CurrentUser
    }

    fun auditContext(request: HttpServletRequest): AuditContext = AuditContext(
        actorUserId = findCurrentUser()?.userId,
        correlationId = CorrelationIdFilter.from(request),
        source = AuditSource.WEB_API,
        clientIpHash = request.remoteAddr?.let { sha256(it) },
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
