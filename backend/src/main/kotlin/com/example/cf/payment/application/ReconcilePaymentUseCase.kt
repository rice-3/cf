package com.example.cf.payment.application

import com.example.cf.payment.domain.model.PaymentStatus
import com.example.cf.payment.domain.repository.PaymentRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.error.DependencyException
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.id.PaymentId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private const val RESOURCE_TYPE = "Payment"

/**
 * BAT-007 決済照合（基本設計 §8.1、詳細設計 §10.5）。
 * Webhookが届かずUNKNOWNのまま滞留した決済をProviderへ照会し、状態を確定させる。
 * API-PY-002（OPERATORによる手動照合）からも同じ処理を使用する。
 */
interface ReconcilePaymentUseCase {
    /** 単一決済の照合。@return 照合後の決済状態 */
    fun execute(paymentId: PaymentId, audit: AuditContext): PaymentStatus

    /** UNKNOWN滞留分をまとめて照合する。@return 照合件数 */
    fun executeBatch(limit: Int, audit: AuditContext): Int
}

@Service
class ReconcilePaymentService(
    private val paymentRepository: PaymentRepository,
    private val steps: PaymentTransactionSteps,
    private val gateway: PaymentGatewayPort,
) : ReconcilePaymentUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun executeBatch(limit: Int, audit: AuditContext): Int {
        val targets = paymentRepository.findReconcileTargets(limit)
        targets.forEach { execute(it, audit) }
        if (targets.isNotEmpty()) {
            log.info("BAT-007 reconciled {} payments", targets.size)
        }
        return targets.size
    }

    override fun execute(paymentId: PaymentId, audit: AuditContext): PaymentStatus {
        val payment = paymentRepository.findById(paymentId)
            ?: throw ResourceNotFoundException("PAYMENT_NOT_FOUND", "Payment ${paymentId.value} is not found")
        val providerPaymentId = payment.providerPaymentId ?: return payment.status

        val result = try {
            gateway.getPayment(providerPaymentId)
        } catch (e: DependencyException) {
            log.warn("Reconciliation call failed: paymentId={}", paymentId.value, e)
            return payment.status
        }

        when (result.status) {
            ProviderPaymentStatus.SUCCEEDED ->
                steps.applyReconciledSuccess(paymentId, result.providerPaymentId, audit)
            ProviderPaymentStatus.FAILED ->
                steps.applyReconciledFailure(paymentId, result.declineReason, audit)
            // 依然として不明な決済は次回以降に再照会する。24時間超過分は手動対応（詳細設計 §9）
            ProviderPaymentStatus.ACCEPTED, ProviderPaymentStatus.UNKNOWN -> Unit
        }
        return paymentRepository.findById(paymentId)?.status ?: payment.status
    }
}
