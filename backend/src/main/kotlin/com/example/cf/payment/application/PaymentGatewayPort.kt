package com.example.cf.payment.application

import org.springframework.boot.context.properties.ConfigurationProperties

/** 決済Provider標準応答（詳細設計 §10.4: Provider SDK型を外へ出さない）。 */
data class ProviderPaymentResult(
    val providerPaymentId: String,
    val status: ProviderPaymentStatus,
    val declineReason: String? = null,
)

/** Provider状態の内部標準型。 */
enum class ProviderPaymentStatus {
    ACCEPTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

/** Provider返金応答（§10.4 requestRefund / getRefund）。 */
data class ProviderRefundResult(
    val providerRefundId: String,
    val status: ProviderRefundStatus,
    val errorCode: String? = null,
)

enum class ProviderRefundStatus {
    SUCCEEDED,
    FAILED,
    UNKNOWN,
}

/** 検証済みWebhookイベント（§10.4 verifyWebhook）。 */
data class VerifiedWebhookEvent(
    val eventId: String,
    val eventType: String,
    val providerPaymentId: String,
    val payloadHash: String,
    val payload: Map<String, Any?>,
)

/**
 * 決済Sandbox Port（詳細設計 §10.4、基本設計 §9.4）。
 * 実決済事業者へは接続せず、教育用Sandboxのみを対象とする。
 */
interface PaymentGatewayPort {

    /** 決済要求（Timeout 10秒）。Outbox Workerから呼ばれる。 */
    fun createPayment(paymentId: String, amount: Long): ProviderPaymentResult

    /** 決済照会（Timeout 5秒）。BAT-007 決済照合で使用する（基本設計 §8.1）。 */
    fun getPayment(providerPaymentId: String): ProviderPaymentResult

    /**
     * 返金要求（Timeout 10秒）。BAT-004 返金実行が使用する。
     * `idempotencyKey` を外部冪等キーとして渡すことで、再試行時の二重返金を防ぐ（基本設計 §9.4）。
     */
    fun requestRefund(providerPaymentId: String, amount: Long, idempotencyKey: String): ProviderRefundResult

    /** 返金照会（Timeout 5秒）。結果不明時の確認に使用する。 */
    fun getRefund(providerRefundId: String): ProviderRefundResult

    /**
     * Webhook署名・時刻・イベントIDを検証する（Timeout 1秒相当、基本設計 §9.4）。
     * 検証失敗はnullを返し、Controllerが401へ変換する。
     */
    fun verifyWebhook(signature: String?, timestamp: String?, rawBody: String): VerifiedWebhookEvent?
}

/** 決済Sandbox設定（§13.1 環境変数）。 */
@ConfigurationProperties(prefix = "cf.payment")
data class PaymentProperties(
    /**
     * Webhook署名検証の共有秘密。dev以上はSecrets Managerから注入する（§11.4）。
     * localの既定値は教育用の固定文字列で、実運用の秘密情報ではない。
     */
    val webhookSecret: String = "local-sandbox-webhook-secret",
    /** 署名タイムスタンプの許容差（§6.8: 5分）。 */
    val webhookToleranceSeconds: Long = 300,
)
