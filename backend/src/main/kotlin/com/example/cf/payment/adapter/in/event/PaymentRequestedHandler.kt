package com.example.cf.payment.adapter.`in`.event

import com.example.cf.payment.application.StartPaymentProcessingUseCase
import com.example.cf.shared.outbox.OutboxMessage
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * PaymentRequestedを購読して外部決済APIを呼び出す（詳細設計 §5.3.1）。
 *
 * 支援作成トランザクションでは外部呼出しを行わず、Outbox Worker（BAT-006）が
 * 配送したイベントを契機にこのHandlerが決済を開始する。
 */
@Component
class PaymentRequestedHandler(
    private val startPaymentProcessing: StartPaymentProcessingUseCase,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun handle(message: OutboxMessage) {
        if (message.eventType != "PaymentRequested") {
            return
        }
        val paymentId = message.aggregateId
        val amount = (message.payload["amount"] as? Number)?.toLong()
        if (amount == null) {
            log.error("PaymentRequested payload is missing amount: eventId={}", message.eventId)
            return
        }
        startPaymentProcessing.execute(paymentId, amount)
    }
}
