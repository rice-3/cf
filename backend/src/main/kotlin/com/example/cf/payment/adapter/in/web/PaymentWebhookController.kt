package com.example.cf.payment.adapter.`in`.web

import com.example.cf.payment.application.HandlePaymentWebhookUseCase
import com.example.cf.payment.application.PaymentGatewayPort
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.AuditSource
import com.example.cf.shared.kernel.error.AuthenticationRequiredException
import com.example.cf.shared.web.CorrelationIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 決済Webhook受信API（API-PY-001、詳細設計 §6.8 / §5.4）。
 *
 * 認証はProvider署名検証のみで行うため、Security設定でpermitAllとし、
 * 署名不正は401を返す。正常・重複ともに204を返す（§5.4-9）。
 */
@RestController
class PaymentWebhookController(
    private val gateway: PaymentGatewayPort,
    private val handleWebhook: HandlePaymentWebhookUseCase,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/v1/payments/webhooks")
    fun receive(
        @RequestHeader(name = "X-Sandbox-Signature", required = false) signature: String?,
        @RequestHeader(name = "X-Sandbox-Timestamp", required = false) timestamp: String?,
        @RequestBody rawBody: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        // Payloadは最大256KB（§6.8）
        if (rawBody.toByteArray().size > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build()
        }

        val verified = gateway.verifyWebhook(signature, timestamp, rawBody)
        if (verified == null) {
            // 応答形式はGlobalExceptionHandlerのProblem Detailsへ統一する（§6.3）
            log.warn("Rejected webhook with invalid signature or timestamp")
            throw AuthenticationRequiredException(
                message = "Webhook signature verification failed",
                errorCode = "PAYMENT_SIGNATURE_INVALID",
            )
        }

        // Webhookは外部からの非認証要求のためactorはSYSTEM扱いとする（§3.5）
        val audit = AuditContext(
            actorUserId = null,
            correlationId = CorrelationIdFilter.from(request),
            source = AuditSource.SYSTEM,
        )
        handleWebhook.execute(verified, audit)
        return ResponseEntity.noContent().build()
    }

    companion object {
        private const val MAX_BODY_BYTES = 256 * 1024
    }
}
