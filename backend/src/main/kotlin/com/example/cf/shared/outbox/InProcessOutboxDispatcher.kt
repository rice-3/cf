package com.example.cf.shared.outbox

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * アプリ内Handlerへ配送するDispatcher（基本設計 §8.1 BAT-006「SQSまたはアプリ内」、ADR-0008）。
 *
 * Spring の ApplicationEvent として配送し、各コンテキストの `@EventListener` が購読する。
 * 単一backendプロセス（ADR-0001）である限りSQSを挟んでも配送先は同一JVMのまま変わらないため、
 * **アプリ内配送を正式な構成として採用する**（ADR-0008）。
 * 将来Workerを別サービスへ切り出す場合はADR-0008を差し替え、本クラスをSQS Adapterへ置き換える。
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
