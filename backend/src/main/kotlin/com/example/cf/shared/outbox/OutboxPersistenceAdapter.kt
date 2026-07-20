package com.example.cf.shared.outbox

import com.example.cf.shared.kernel.event.DomainEvent
import com.example.cf.shared.kernel.id.CorrelationId
import com.example.cf.shared.kernel.id.UlidGenerator
import tools.jackson.databind.ObjectMapper
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant

/** outbox_event テーブル（詳細設計 §8.18）。 */
@Entity
@Table(name = "outbox_event")
class OutboxEventJpaEntity(
    @Id
    @Column(name = "event_id", length = 26)
    var eventId: String = "",

    @Column(name = "aggregate_type", length = 100, nullable = false)
    var aggregateType: String = "",

    @Column(name = "aggregate_id", length = 26, nullable = false)
    var aggregateId: String = "",

    @Column(name = "event_type", length = 200, nullable = false)
    var eventType: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    var payload: Map<String, Any?> = emptyMap(),

    @Column(name = "occurred_at", nullable = false)
    var occurredAt: Instant = Instant.EPOCH,

    @Column(name = "publish_status", length = 30, nullable = false)
    var publishStatus: String = "PENDING",

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null,

    @Column(name = "published_at")
    var publishedAt: Instant? = null,
)

@Repository
interface OutboxEventJpaRepository : JpaRepository<OutboxEventJpaEntity, String> {

    /**
     * 配送対象を FOR UPDATE SKIP LOCKED で取得する（詳細設計 §9.1）。
     * 複数Workerが同時起動しても同一イベントを二重配送しない（§5.5）。
     */
    @Query(
        value = """
            select * from outbox_event
             where publish_status in ('PENDING', 'ERROR')
               and coalesce(next_retry_at, occurred_at) <= :now
             order by occurred_at
             for update skip locked
             limit :limit
        """,
        nativeQuery = true,
    )
    fun lockPendingBatch(
        @Param("now") now: Instant,
        @Param("limit") limit: Int,
    ): List<OutboxEventJpaEntity>
}

/**
 * Outbox追記Adapter。業務トランザクションに参加して記録する（基本設計 §4.7）。
 * 配送は BAT-006 Outbox Worker が行う（[OutboxWorker]）。
 */
@Component
class OutboxPersistenceAdapter(
    private val repository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    private val idGenerator: UlidGenerator,
    private val clock: Clock,
) : OutboxAppendPort {

    override fun append(event: DomainEvent, correlationId: CorrelationId) {
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.convertValue(event, Map::class.java) as Map<String, Any?>
        repository.save(
            OutboxEventJpaEntity(
                eventId = idGenerator.next(),
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                eventType = event.eventType,
                payload = payload + mapOf("correlationId" to correlationId.value),
                occurredAt = clock.instant(),
                publishStatus = "PENDING",
            ),
        )
    }
}
