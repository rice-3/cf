package com.example.cf.payment.application

import com.example.cf.payment.domain.event.PaymentRequested
import com.example.cf.payment.domain.model.Payment
import com.example.cf.payment.domain.repository.PaymentRepository
import com.example.cf.shared.kernel.id.PaymentId
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.outbox.OutboxAppendPort
import com.example.cf.shared.kernel.id.CorrelationId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Paymentコンテキスト公開契約: 支援申込時の内部Payment生成（§5.3 UC-FD-001）。
 * 呼出し元（Funding）のトランザクションに参加し、PaymentRequestedをOutboxへ積む。
 * 外部決済API呼出しはOutbox Worker（工程7）が行う（§5.3.1）。
 */
interface CreatePaymentForSupportUseCase {
    fun execute(supportId: SupportId, amount: Money, correlationId: CorrelationId): PaymentId
}

@Service
class CreatePaymentForSupportService(
    private val paymentRepository: PaymentRepository,
    private val outbox: OutboxAppendPort,
    private val clock: Clock,
    private val idGenerator: UlidGenerator,
) : CreatePaymentForSupportUseCase {

    @Transactional
    override fun execute(supportId: SupportId, amount: Money, correlationId: CorrelationId): PaymentId {
        val now = clock.instant()
        val payment = Payment.create(
            id = PaymentId.newId(idGenerator),
            supportId = supportId,
            provider = SANDBOX_PROVIDER,
            amount = amount,
            now = now,
        )
        paymentRepository.save(payment)
        outbox.append(
            PaymentRequested(
                paymentId = payment.id,
                supportId = supportId,
                amount = amount.amount,
                provider = payment.provider,
                occurredAt = now,
            ),
            correlationId,
        )
        return payment.id
    }

    companion object {
        /** 決済Sandbox（基本設計 §9.4）。実在決済事業者へは接続しない。 */
        const val SANDBOX_PROVIDER = "SANDBOX"
    }
}
