package com.example.cf.payment.adapter.`in`.batch

import com.example.cf.payment.application.ExecuteRefundUseCase
import com.example.cf.payment.application.PaymentTransactionSteps
import com.example.cf.payment.application.ReconcilePaymentUseCase
import com.example.cf.shared.batch.BatchProperties
import com.example.cf.shared.batch.batchAuditContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Paymentコンテキストの定期バッチ（BAT-004 返金実行 / BAT-007 決済照合、基本設計 §8.1）。
 */
@Component
class PaymentScheduledBatches(
    private val steps: PaymentTransactionSteps,
    private val executeRefund: ExecuteRefundUseCase,
    private val reconcilePayment: ReconcilePaymentUseCase,
    private val properties: BatchProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** BAT-004 返金実行（1分ごと）。 */
    @Scheduled(fixedDelayString = "\${cf.batch.refund-interval-ms:60000}")
    fun executeRefunds() {
        if (!properties.enabled) return
        runCatching { runRefundBatch() }
            .onFailure { log.error("BAT-004 refund batch failed", it) }
    }

    /**
     * 対象を取得して1件ずつ実行する。
     * 外部決済APIを長いトランザクション内で呼ばないよう、対象取得と実行を分ける（§5.3.1）。
     */
    fun runRefundBatch(): Int {
        val audit = batchAuditContext()
        val targets = steps.lockRefundTargets(properties.workerBatchSize)
        targets.forEach { refundId -> executeRefund.execute(refundId, audit) }
        if (targets.isNotEmpty()) {
            log.info("BAT-004 processed {} refunds", targets.size)
        }
        return targets.size
    }

    /** BAT-007 決済照合（15分ごと）。結果不明の決済をProviderへ照会して確定させる。 */
    @Scheduled(fixedDelayString = "\${cf.batch.reconcile-interval-ms:900000}")
    fun reconcilePayments() {
        if (!properties.enabled) return
        runCatching { reconcilePayment.executeBatch(properties.workerBatchSize, batchAuditContext()) }
            .onFailure { log.error("BAT-007 reconciliation batch failed", it) }
    }
}
