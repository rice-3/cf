package com.example.cf.payment.application

import com.example.cf.audit.application.AuditRecordPort
import com.example.cf.audit.application.record
import com.example.cf.funding.application.SupportReferenceQuery
import com.example.cf.funding.application.SupportRefundPort
import com.example.cf.payment.domain.model.Refund
import com.example.cf.payment.domain.model.RefundReasonCode
import com.example.cf.payment.domain.model.RefundStatus
import com.example.cf.payment.domain.repository.PaymentRepository
import com.example.cf.payment.domain.repository.RefundRepository
import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.error.DependencyException
import com.example.cf.shared.kernel.error.InvalidStateException
import com.example.cf.shared.kernel.error.ResourceNotFoundException
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.money.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private const val RESOURCE_TYPE = "Refund"

/**
 * 返金要求の作成（§4.5 request）。
 * BAT-003（不成立案件の一括作成）と API-RF-001（OPERATOR手動要求）の双方から使用する。
 */
interface CreateRefundUseCase {
    /** 単一支援に対する返金要求。既に有効な返金がある場合は例外。 */
    fun execute(
        supportId: SupportId,
        reasonCode: RefundReasonCode,
        comment: String?,
        amount: Money?,
        audit: AuditContext,
    ): RefundId

    /** 不成立プロジェクトの全返金対象を作成する（BAT-003）。作成件数を返す。 */
    fun createForFailedProject(projectId: ProjectId, audit: AuditContext): Int
}

@Service
class CreateRefundService(
    private val refundRepository: RefundRepository,
    private val paymentRepository: PaymentRepository,
    private val supportReferenceQuery: SupportReferenceQuery,
    private val supportRefundPort: SupportRefundPort,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : CreateRefundUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun execute(
        supportId: SupportId,
        reasonCode: RefundReasonCode,
        comment: String?,
        amount: Money?,
        audit: AuditContext,
    ): RefundId {
        refundRepository.findActiveBySupportId(supportId)?.let {
            throw InvalidStateException(
                "REFUND_ALREADY_EXISTS",
                "Support ${supportId.value} already has an active refund ${it.id.value}",
            )
        }

        val target = supportReferenceQuery.findRefundTarget(supportId)
            ?: throw InvalidStateException(
                "REFUND_NOT_ALLOWED",
                "Support ${supportId.value} is not refundable",
            )
        val payment = paymentRepository.findById(PaymentId(target.paymentId))
            ?: throw ResourceNotFoundException("PAYMENT_NOT_FOUND", "Payment ${target.paymentId} is not found")
        if (payment.status != com.example.cf.payment.domain.model.PaymentStatus.SUCCEEDED) {
            throw InvalidStateException(
                "REFUND_NOT_ALLOWED",
                "Payment ${payment.id.value} is not refundable in status ${payment.status}",
            )
        }

        val refund = Refund.request(
            id = RefundId.newId(idGenerator),
            paymentId = payment.id,
            supportId = supportId,
            amount = amount ?: Money.of(target.amount),
            reasonCode = reasonCode,
            comment = comment,
            now = clock.instant(),
        )
        refundRepository.save(refund)
        supportRefundPort.requireRefund(supportId, audit)
        auditPort.record(audit, "REFUND_REQUEST", RESOURCE_TYPE, refund.id.value, "SUCCESS")
        return refund.id
    }

    /**
     * BAT-003 返金対象作成。ProjectFailedを契機に不成立案件の返金要求を一括生成する。
     * 既に有効な返金がある支援は読み飛ばすため、イベントを重複受信しても二重作成されない。
     */
    @Transactional
    override fun createForFailedProject(projectId: ProjectId, audit: AuditContext): Int {
        var created = 0
        supportReferenceQuery.findRefundTargets(projectId).forEach { target ->
            val supportId = SupportId(target.supportId)
            if (refundRepository.findActiveBySupportId(supportId) != null) {
                return@forEach
            }
            val refund = Refund.request(
                id = RefundId.newId(idGenerator),
                paymentId = PaymentId(target.paymentId),
                supportId = supportId,
                amount = Money.of(target.amount),
                reasonCode = RefundReasonCode.PROJECT_FAILED,
                comment = null,
                now = clock.instant(),
            )
            refundRepository.save(refund)
            supportRefundPort.requireRefund(supportId, audit)
            auditPort.record(audit, "REFUND_REQUEST", RESOURCE_TYPE, refund.id.value, "SUCCESS")
            created += 1
        }
        if (created > 0) {
            log.info("Created {} refund requests for failed project {}", created, projectId.value)
        }
        return created
    }
}

/**
 * 返金の再実行要求（API-RF-002、§4.5 retry）。
 * RETRY_WAITまたはFAILEDの返金をREQUESTEDへ戻し、次回のBAT-004で再実行させる。
 * 再実行操作そのものを監査ログへ記録する（基本設計 §8.2）。
 */
interface RetryRefundUseCase {
    fun execute(refundId: RefundId, audit: AuditContext): RefundStatus
}

@Service
class RetryRefundService(
    private val refundRepository: RefundRepository,
    private val auditPort: AuditRecordPort,
    private val clock: Clock,
) : RetryRefundUseCase {

    @Transactional
    override fun execute(refundId: RefundId, audit: AuditContext): RefundStatus {
        val refund = refundRepository.findByIdForUpdate(refundId)
            ?: throw ResourceNotFoundException("REFUND_NOT_FOUND", "Refund ${refundId.value} is not found")

        refund.retry(clock.instant())
        refundRepository.save(refund)
        auditPort.record(audit, "REFUND_RETRY", RESOURCE_TYPE, refund.id.value, "SUCCESS")
        return refund.status
    }
}

/**
 * 返金実行（§4.5 start/succeed/fail）。BAT-004が1件ずつ呼び出す。
 * 外部呼出しの前後を短いトランザクションに分ける（§5.3.1と同方針）。
 */
interface ExecuteRefundUseCase {
    fun execute(refundId: RefundId, audit: AuditContext)
}

@Service
class ExecuteRefundService(
    private val steps: PaymentTransactionSteps,
    private val gateway: PaymentGatewayPort,
) : ExecuteRefundUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(refundId: RefundId, audit: AuditContext) {
        val started = steps.startRefund(refundId, audit) ?: return

        val result = try {
            gateway.requestRefund(
                providerPaymentId = started.providerPaymentId,
                amount = started.amount,
                // 再試行しても二重返金にならないよう返金IDを外部冪等キーにする（基本設計 §9.4）
                idempotencyKey = refundId.value,
            )
        } catch (e: DependencyException) {
            log.warn("Refund provider call failed: refundId={}", refundId.value, e)
            steps.finishRefundFailure(refundId, "PROVIDER_UNAVAILABLE", audit)
            return
        }

        when (result.status) {
            ProviderRefundStatus.SUCCEEDED -> steps.finishRefundSuccess(refundId, result.providerRefundId, audit)
            ProviderRefundStatus.FAILED -> steps.finishRefundFailure(refundId, result.errorCode, audit)
            // 結果不明は失敗扱いで再試行する。Provider側は外部冪等キーで二重返金を防ぐ。
            ProviderRefundStatus.UNKNOWN -> steps.finishRefundFailure(refundId, "PROVIDER_UNKNOWN", audit)
        }
    }
}
