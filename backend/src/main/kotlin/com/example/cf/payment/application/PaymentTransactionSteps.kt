package com.example.cf.payment.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.funding.application.SupportPaymentResultPort
import com.example.cf.funding.application.SupportRefundPort
import com.example.cf.payment.domain.model.PaymentStatus
import com.example.cf.payment.domain.repository.PaymentRepository
import com.example.cf.payment.domain.repository.RefundRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.outbox.OutboxAppendPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 外部呼出しを挟む処理のトランザクション境界（§5.3.1）。
 *
 * `REQUIRES_NEW` は自己呼出しではプロキシを経由せず有効にならないため、
 * 呼出し元のUseCaseとは別Beanへ切り出している。
 */
@Service
class PaymentTransactionSteps(
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
    private val supportResultPort: SupportPaymentResultPort,
    private val supportRefundPort: SupportRefundPort,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ---- 決済開始（§5.3.1） --------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPaymentProcessing(id: PaymentId, providerPaymentId: String?) {
        val payment = paymentRepository.findByIdForUpdate(id) ?: return
        payment.startProcessing(providerPaymentId, clock.instant())
        paymentRepository.save(payment)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPaymentFailed(id: PaymentId, declineReason: String?) {
        val payment = paymentRepository.findByIdForUpdate(id) ?: return
        val now = clock.instant()
        payment.startProcessing(null, now)
        payment.fail(declineReason, now)
        paymentRepository.save(payment)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markPaymentUnknown(id: PaymentId) {
        val payment = paymentRepository.findByIdForUpdate(id) ?: return
        val now = clock.instant()
        payment.startProcessing(null, now)
        payment.markUnknown(now)
        paymentRepository.save(payment)
    }

    // ---- BAT-007 決済照合 ----------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun findProviderPaymentId(id: PaymentId): String? =
        paymentRepository.findById(id)?.providerPaymentId

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun applyReconciledSuccess(id: PaymentId, providerPaymentId: String, audit: AuditContext) {
        val payment = paymentRepository.findByIdForUpdate(id) ?: return
        if (payment.status != PaymentStatus.UNKNOWN) return

        val event = payment.succeed(providerPaymentId, clock.instant())
        paymentRepository.save(payment)
        supportResultPort.confirmBySupportId(payment.supportId, audit)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "PAYMENT_RECONCILE_SUCCEEDED", "Payment", payment.id.value, "SUCCESS")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun applyReconciledFailure(id: PaymentId, failureCode: String?, audit: AuditContext) {
        val payment = paymentRepository.findByIdForUpdate(id) ?: return
        if (payment.status != PaymentStatus.UNKNOWN) return

        val event = payment.fail(failureCode, clock.instant())
        paymentRepository.save(payment)
        supportResultPort.failBySupportId(payment.supportId, audit)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "PAYMENT_RECONCILE_FAILED", "Payment", payment.id.value, "SUCCESS")
    }

    // ---- BAT-004 返金実行 ----------------------------------------------------

    /** 返金実行に必要な情報。Provider呼出し中はトランザクションを保持しない。 */
    data class StartedRefund(val providerPaymentId: String, val amount: Long)

    // SELECT ... FOR UPDATE は読み取り専用トランザクションでは実行できないため readOnly にしない
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun lockRefundTargets(limit: Int): List<RefundId> =
        refundRepository.lockExecutableBatch(clock.instant(), limit).map { it.id }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun startRefund(refundId: RefundId, audit: AuditContext): StartedRefund? {
        val refund = refundRepository.findByIdForUpdate(refundId) ?: return null
        val payment = paymentRepository.findByIdForUpdate(refund.paymentId) ?: return null
        val providerPaymentId = payment.providerPaymentId
        if (providerPaymentId == null) {
            log.warn("Refund {} has no provider payment id; skipped", refundId.value)
            return null
        }

        val now = clock.instant()
        refund.start(now)
        refundRepository.save(refund)
        // Paymentは初回のみREFUND_PENDINGへ遷移させる（再試行時は既に遷移済み）
        if (payment.status == PaymentStatus.SUCCEEDED) {
            payment.requireRefund(now)
            paymentRepository.save(payment)
        }
        supportRefundPort.startRefund(refund.supportId, audit)
        return StartedRefund(providerPaymentId, refund.amount.amount)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finishRefundSuccess(refundId: RefundId, providerRefundId: String, audit: AuditContext) {
        val refund = refundRepository.findByIdForUpdate(refundId) ?: return
        val now = clock.instant()
        val event = refund.succeed(providerRefundId, now)
        refundRepository.save(refund)

        paymentRepository.findByIdForUpdate(refund.paymentId)?.let { payment ->
            payment.markRefunded(now)
            paymentRepository.save(payment)
        }
        supportRefundPort.markRefunded(refund.supportId, audit)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "REFUND_SUCCEEDED", "Refund", refund.id.value, "SUCCESS")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finishRefundFailure(refundId: RefundId, errorCode: String?, audit: AuditContext) {
        val refund = refundRepository.findByIdForUpdate(refundId) ?: return
        val permanentFailure = refund.fail(errorCode, clock.instant())
        refundRepository.save(refund)

        if (permanentFailure != null) {
            // 上限超過。OPERATORが原因確認後に再実行する（基本設計 §8.2）
            supportRefundPort.failRefund(refund.supportId, audit)
            outbox.append(permanentFailure, audit.correlationId)
            auditPort.record(audit, "REFUND_FAILED", "Refund", refund.id.value, "FAILURE")
            log.error("Refund exhausted retries: refundId={} errorCode={}", refundId.value, errorCode)
        }
    }
}
