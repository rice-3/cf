package com.example.cf.shared.outbox

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * アプリ内Handlerへ配送するDispatcher（§9.1: SQSまたはアプリ内）。
 *
 * 教育用モジュラーモノリスでは Spring の ApplicationEvent として配送し、
 * 各コンテキストの `@EventListener` が購読する。
 * dev以上でSQSへ切り替える場合は本クラスをSQS Adapterへ差し替える（ADR候補）。
 */
@Component
class InProcessOutboxDispatcher(
    private val publisher: ApplicationEventPublisher,
) : OutboxDispatcher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun dispatch(event: OutboxEventJpaEntity) {
        log.debug("Dispatching outbox event: type={} aggregate={}", event.eventType, event.aggregateId)
        publisher.publishEvent(
            OutboxMessage(
                eventId = event.eventId,
                eventType = event.eventType,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                payload = event.payload,
            ),
        )
    }
}

/**
 * 配送されたイベントのアプリ内表現。
 * 購読側はpayloadのMapから必要な値のみを読み取る（集約への直接依存を持たない）。
 */
data class OutboxMessage(
    val eventId: String,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val payload: Map<String, Any?>,
)
