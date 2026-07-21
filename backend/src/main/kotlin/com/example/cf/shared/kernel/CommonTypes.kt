package com.example.cf.shared.kernel

import com.example.cf.shared.kernel.id.CorrelationId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/**
 * 楽観ロック版数（詳細設計 §3.1）。0以上。
 */
@JvmInline
value class Version(val value: Long) {
    init {
        require(value >= 0) { "Version must be >= 0: $value" }
    }

    fun increment(): Version = Version(value + 1)
}

/**
 * 冪等キー（詳細設計 §3.1）。1～100文字、許可文字のみ。
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        require(value.length in 1..100) { "IdempotencyKey must be 1..100 characters" }
        require(PATTERN.matches(value)) { "IdempotencyKey contains disallowed characters" }
    }

    companion object {
        private val PATTERN = Regex("[A-Za-z0-9_\\-]{1,100}")
    }
}

/** ロールコード（基本設計 §2.1）。 */
enum class RoleCode {
    GUEST,
    SUPPORTER,
    OWNER,
    REVIEWER,
    OPERATOR,
    ADMIN,
    AUDITOR,
    DEVELOPER,
    AI_AGENT,
}

/**
 * 認証済み利用者（詳細設計 §3.2）。
 */
data class CurrentUser(
    val userId: UserId,
    val roles: Set<RoleCode>,
    val authenticatedAt: Instant,
) {
    fun has(role: RoleCode): Boolean = role in roles

    /** ロール認可（§5.1-3）。画面制御に依存せずAPI側で必ず検証する（基本設計 §2.3）。 */
    fun requireRole(role: RoleCode) {
        if (!has(role)) {
            throw com.example.cf.shared.kernel.error.AccessDeniedException(message = "Role $role is required")
        }
    }
}

/** 監査記録の発生元。 */
enum class AuditSource { WEB_API, BATCH, SYSTEM }

/**
 * 共通監査情報（詳細設計 §3.5）。
 */
data class AuditContext(
    val actorUserId: UserId?,
    val correlationId: CorrelationId,
    val source: AuditSource,
    val clientIpHash: String? = null,
)

/**
 * ページング結果共通型（詳細設計 §3.4）。
 */
data class PageResult<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun <T> of(items: List<T>, page: Int, size: Int, totalElements: Long): PageResult<T> {
            val totalPages = if (size == 0) 0 else ((totalElements + size - 1) / size).toInt()
            return PageResult(items, page, size, totalElements, totalPages)
        }
    }
}
