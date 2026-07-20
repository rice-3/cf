package com.example.cf.shared.kernel.error

/**
 * 例外階層（詳細設計 §12.1）。
 * HTTPステータスへの変換は adapter.in.web の例外ハンドラで行う。
 */
abstract class ApplicationException(
    /** 定義済みエラーコード（§12.2）。 */
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 400: 形式・入力検証エラー。 */
class ValidationException(
    errorCode: String = "VALIDATION_ERROR",
    message: String,
    val fieldErrors: List<FieldError> = emptyList(),
) : ApplicationException(errorCode, message) {
    data class FieldError(val field: String, val code: String, val message: String)
}

/** 401: 未認証。Webhook署名検証失敗（§6.8）等もこの区分で扱う。 */
class AuthenticationRequiredException(
    message: String = "Authentication is required",
    errorCode: String = "AUTHENTICATION_REQUIRED",
) : ApplicationException(errorCode, message)

/** 403: 権限・所有権不足。 */
class AccessDeniedException(
    errorCode: String = "ACCESS_DENIED",
    message: String = "Access is denied",
) : ApplicationException(errorCode, message)

/** 404: 対象なし。 */
class ResourceNotFoundException(
    errorCode: String,
    message: String,
) : ApplicationException(errorCode, message)

/** 409系の基底。 */
abstract class ConflictException(
    errorCode: String,
    message: String,
) : ApplicationException(errorCode, message)

/** 409: 現在状態で操作不可。 */
class InvalidStateException(
    errorCode: String,
    message: String,
) : ConflictException(errorCode, message)

/** 409: 楽観ロック競合。 */
class OptimisticLockConflictException(
    message: String = "The resource was updated by another user",
) : ConflictException("OPTIMISTIC_LOCK_CONFLICT", message)

/** 409: 冪等キー競合（同一キー処理中／Body相違）。 */
class IdempotencyConflictException(
    errorCode: String = "IDEMPOTENCY_IN_PROGRESS",
    message: String,
) : ConflictException(errorCode, message)

/** 422: 業務条件不充足。 */
class BusinessRuleViolationException(
    errorCode: String,
    message: String,
    val violations: List<String> = emptyList(),
) : ApplicationException(errorCode, message)

/** 503: 外部依存の一時障害。 */
class DependencyException(
    errorCode: String = "DEPENDENCY_UNAVAILABLE",
    message: String,
    cause: Throwable? = null,
) : ApplicationException(errorCode, message, cause)
