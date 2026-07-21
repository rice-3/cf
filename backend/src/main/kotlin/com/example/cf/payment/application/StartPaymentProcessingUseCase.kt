package com.example.cf.payment.application

import com.example.cf.shared.kernel.error.DependencyException
import com.example.cf.shared.kernel.id.PaymentId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 決済開始（§5.3.1: Outbox配送後の外部呼出し）。
 *
 * 外部呼出しの前後を短いトランザクションに分ける。Sandbox応答がTimeout等で
 * 不明な場合はPayment=UNKNOWNとし、BAT-007 決済照合の対象とする（基本設計 §8.1、§10.5）。
 */
interface StartPaymentProcessingUseCase {
    fun execute(paymentId: String, amount: Long)
}

@Service
class StartPaymentProcessingService(
    private val steps: PaymentTransactionSteps,
    private val gateway: PaymentGatewayPort,
) : StartPaymentProcessingUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(paymentId: String, amount: Long) {
        val id = PaymentId(paymentId)
        val result = try {
            gateway.createPayment(paymentId, amount)
        } catch (e: DependencyException) {
            steps.markPaymentUnknown(id)
            log.warn("Payment provider call failed, marked UNKNOWN: paymentId={}", paymentId, e)
            return
        }

        when (result.status) {
            ProviderPaymentStatus.ACCEPTED, ProviderPaymentStatus.SUCCEEDED ->
                steps.markPaymentProcessing(id, result.providerPaymentId)
            ProviderPaymentStatus.FAILED -> steps.markPaymentFailed(id, result.declineReason)
            ProviderPaymentStatus.UNKNOWN -> steps.markPaymentUnknown(id)
        }
    }
}
