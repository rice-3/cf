package com.example.cf.payment.adapter.out.gateway

import com.example.cf.payment.application.PaymentGatewayPort
import com.example.cf.payment.application.PaymentProperties
import com.example.cf.payment.application.ProviderPaymentResult
import com.example.cf.payment.application.ProviderPaymentStatus
import com.example.cf.payment.application.ProviderRefundResult
import com.example.cf.payment.application.ProviderRefundStatus
import com.example.cf.payment.application.VerifiedWebhookEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * 決済Sandbox Adapter（詳細設計 §10.4、基本設計 §9.4）。
 *
 * 実決済事業者へは接続せず、教育用に内部で決済受付を模倣する。
 * カード番号等の機微情報は一切扱わず、ログにも出力しない（§9.4）。
 * dev以上で実Sandboxへ接続する場合も、Provider SDK型はこのクラス内へ閉じ込め、
 * 上位へは [ProviderPaymentResult] のみを返す。
 */
@Component
class SandboxPaymentGatewayAdapter(
    private val properties: PaymentProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : PaymentGatewayPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 教育用の疑似Provider台帳（paymentId → providerPaymentId）。 */
    private val accepted = ConcurrentHashMap<String, String>()

    /** 返金の外部冪等キー → 結果。 */
    private val refunds = ConcurrentHashMap<String, ProviderRefundResult>()

    override fun createPayment(paymentId: String, amount: Long): ProviderPaymentResult {
        val providerPaymentId = accepted.computeIfAbsent(paymentId) { "sbx_${it.lowercase()}" }
        log.info("Sandbox payment accepted: paymentId={} amount={}", paymentId, amount)
        // Sandboxは受付のみ返し、確定はWebhook（API-PY-001）で行う（基本設計 §3.4）
        return ProviderPaymentResult(providerPaymentId, ProviderPaymentStatus.ACCEPTED)
    }

    override fun getPayment(providerPaymentId: String): ProviderPaymentResult = ProviderPaymentResult(providerPaymentId, ProviderPaymentStatus.UNKNOWN)

    /**
     * 教育用Sandboxの返金。外部冪等キーごとに結果を保持し、再試行しても二重返金にならない
     * （基本設計 §9.4「POSTの無条件再試行は禁止。外部冪等キー利用時のみ実施」）。
     */
    override fun requestRefund(
        providerPaymentId: String,
        amount: Long,
        idempotencyKey: String,
    ): ProviderRefundResult {
        val result = refunds.computeIfAbsent(idempotencyKey) {
            ProviderRefundResult(
                providerRefundId = "sbxrf_${idempotencyKey.lowercase()}",
                status = ProviderRefundStatus.SUCCEEDED,
            )
        }
        log.info("Sandbox refund accepted: paymentId={} amount={}", providerPaymentId, amount)
        return result
    }

    override fun getRefund(providerRefundId: String): ProviderRefundResult = refunds.values.firstOrNull { it.providerRefundId == providerRefundId }
        ?: ProviderRefundResult(providerRefundId, ProviderRefundStatus.UNKNOWN)

    /**
     * HMAC-SHA256署名と時刻許容差を検証する（§6.8: 許容5分）。
     * 署名不正・時刻超過・形式不正はnullを返す。
     */
    override fun verifyWebhook(signature: String?, timestamp: String?, rawBody: String): VerifiedWebhookEvent? {
        if (signature.isNullOrBlank() || timestamp.isNullOrBlank()) {
            return null
        }
        val eventTime = runCatching { Instant.parse(timestamp) }.getOrNull() ?: return null
        val skew = abs(eventTime.epochSecond - clock.instant().epochSecond)
        if (skew > properties.webhookToleranceSeconds) {
            log.warn("Webhook timestamp is out of tolerance: skew={}s", skew)
            return null
        }
        if (!constantTimeEquals(signature, sign("$timestamp.$rawBody"))) {
            log.warn("Webhook signature verification failed")
            return null
        }

        @Suppress("UNCHECKED_CAST")
        val payload = runCatching {
            objectMapper.readValue(rawBody, Map::class.java) as Map<String, Any?>
        }.getOrNull() ?: return null

        val eventId = payload["eventId"] as? String ?: return null
        val eventType = payload["eventType"] as? String ?: return null
        val providerPaymentId = payload["providerPaymentId"] as? String ?: return null
        return VerifiedWebhookEvent(
            eventId = eventId,
            eventType = eventType,
            providerPaymentId = providerPaymentId,
            payloadHash = sha256Hex(rawBody),
            payload = payload,
        )
    }

    private fun sign(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.webhookSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** タイミング攻撃を避けるため長さ非依存で比較する。 */
    private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        /** テスト・ローカル動作確認用の署名生成（本番経路では使用しない）。 */
        fun signForTesting(secret: String, timestamp: String, rawBody: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal("$timestamp.$rawBody".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
