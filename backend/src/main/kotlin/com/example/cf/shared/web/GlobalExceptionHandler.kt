package com.example.cf.shared.web

import com.example.cf.shared.kernel.error.ApplicationException
import com.example.cf.shared.kernel.error.AuthenticationRequiredException
import com.example.cf.shared.kernel.error.BusinessRuleViolationException
import com.example.cf.shared.kernel.error.ConflictException
import com.example.cf.shared.kernel.error.DependencyException
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.error.ValidationException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/**
 * RFC 9457 Problem Details形式の例外ハンドラ（基本設計 §6.3、詳細設計 §12.1〜12.2）。
 * 内部例外名、SQL、スタックトレース、秘密情報は返却しない。
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
        request: HttpServletRequest,
    ): ProblemDetail = ProblemDetail.forStatus(status).apply {
        type = URI.create("https://example.invalid/problems/${code.lowercase().replace('_', '-')}")
        title = code
        this.detail = detail
        instance = URI.create(request.requestURI)
        setProperty("code", code)
        setProperty("correlationId", CorrelationIdFilter.from(request).value)
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(e: ValidationException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, e.errorCode, e.message ?: "入力内容を確認してください。", request).apply {
            if (e.fieldErrors.isNotEmpty()) {
                setProperty("errors", e.fieldErrors)
            }
        }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        e: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "入力内容を確認してください。", request).apply {
            setProperty(
                "errors",
                e.bindingResult.fieldErrors.map {
                    mapOf("field" to it.field, "code" to (it.code ?: ""), "message" to (it.defaultMessage ?: ""))
                },
            )
        }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun handleUnreadable(
        e: org.springframework.http.converter.HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "リクエスト形式を確認してください。", request)

    @ExceptionHandler(AuthenticationRequiredException::class)
    fun handleAuthenticationRequired(
        e: AuthenticationRequiredException,
        request: HttpServletRequest,
    ): ProblemDetail = problem(HttpStatus.UNAUTHORIZED, e.errorCode, "認証が必要です。", request)

    @ExceptionHandler(com.example.cf.shared.kernel.error.AccessDeniedException::class)
    fun handleAccessDenied(
        e: com.example.cf.shared.kernel.error.AccessDeniedException,
        request: HttpServletRequest,
    ): ProblemDetail = problem(HttpStatus.FORBIDDEN, e.errorCode, "この操作を実行する権限がありません。", request)

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException::class)
    fun handleSpringAccessDenied(
        e: org.springframework.security.access.AccessDeniedException,
        request: HttpServletRequest,
    ): ProblemDetail = problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "この操作を実行する権限がありません。", request)

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleNotFound(e: ResourceNotFoundException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, e.errorCode, "対象が見つかりません。", request)

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(e: ConflictException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.CONFLICT, e.errorCode, e.message ?: "競合が発生しました。", request)

    @ExceptionHandler(BusinessRuleViolationException::class)
    fun handleBusinessRule(e: BusinessRuleViolationException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, e.errorCode, e.message ?: "業務条件を満たしていません。", request).apply {
            if (e.violations.isNotEmpty()) {
                setProperty("violations", e.violations)
            }
        }

    @ExceptionHandler(DependencyException::class)
    fun handleDependency(e: DependencyException, request: HttpServletRequest): ProblemDetail {
        log.warn("External dependency failure: code={} message={}", e.errorCode, e.message)
        return problem(HttpStatus.SERVICE_UNAVAILABLE, e.errorCode, "外部サービスが一時的に利用できません。", request)
    }

    @ExceptionHandler(ApplicationException::class)
    fun handleApplication(e: ApplicationException, request: HttpServletRequest): ProblemDetail {
        log.error("Unhandled application exception: code={}", e.errorCode, e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, e.errorCode, "処理を完了できませんでした。", request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, request: HttpServletRequest): ProblemDetail {
        // スタックトレースは内部ログのみに出力する（§12.4）
        log.error("Unexpected error", e)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "処理を完了できませんでした。問い合わせ時は相関IDをお伝えください。",
            request,
        )
    }
}
