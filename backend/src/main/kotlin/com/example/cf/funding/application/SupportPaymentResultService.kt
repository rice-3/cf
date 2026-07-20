package com.example.cf.funding.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.funding.domain.repository.SupportRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.outbox.OutboxAppendPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 決済結果のSupportへの反映（UC-PY-001 §5.4-7）。
 * Webhook処理トランザクションに参加する。
 */
@Service
class SupportPaymentResultService(
    private val supportRepository: SupportRepository,
    private val outbox: OutboxAppendPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : SupportPaymentResultPort, SupportRefundPort {

    @Transactional
    override fun confirmBySupportId(supportId: SupportId, audit: AuditContext) {
        val support = getForUpdate(supportId)
        val event = support.confirm(clock.instant())
        supportRepository.save(support)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "SUPPORT_CONFIRM", "Support", support.id.value, "SUCCESS")
    }

    @Transactional
    override fun failBySupportId(supportId: SupportId, audit: AuditContext) {
        val support = getForUpdate(supportId)
        val event = support.failPayment(clock.instant())
        supportRepository.save(support)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "SUPPORT_PAYMENT_FAILED", "Support", support.id.value, "SUCCESS")
    }

    // ---- SupportRefundPort（BAT-003/004からの遷移、§4.3） ---------------------

    @Transactional
    override fun requireRefund(supportId: SupportId, audit: AuditContext) {
        val support = getForUpdate(supportId)
        val event = support.requireRefund(clock.instant())
        supportRepository.save(support)
        outbox.append(event, audit.correlationId)
        auditPort.record(audit, "SUPPORT_REFUND_REQUIRED", "Support", support.id.value, "SUCCESS")
    }

    @Transactional
    override fun startRefund(supportId: SupportId, audit: AuditContext) {
        val support = getForUpdate(supportId)
        support.startRefund(clock.instant())
        supportRepository.save(support)
    }

    @Transactional
    override fun markRefunded(supportId: SupportId, audit: AuditContext) {
        val support = getForUpdate(supportId)
        support.markRefunded(clock.instant())
        supportRepository.save(support)
        auditPort.record(audit, "SUPPORT_REFUNDED", "Support", support.id.value, "SUCCESS")
    }

    @Transactional
    override fun failRefund(supportId: SupportId, audit: AuditContext) {
        val support = getForUpdate(supportId)
        support.failRefund(clock.instant())
        supportRepository.save(support)
        auditPort.record(audit, "SUPPORT_REFUND_FAILED", "Support", support.id.value, "FAILURE")
    }

    private fun getForUpdate(supportId: SupportId) =
        supportRepository.findByIdForUpdate(supportId)
            ?: throw ResourceNotFoundException("SUPPORT_NOT_FOUND", "Support ${supportId.value} is not found")
}
