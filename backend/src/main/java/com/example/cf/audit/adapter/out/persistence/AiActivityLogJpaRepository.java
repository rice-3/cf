package com.example.cf.audit.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** ai_activity_log用Spring Data Repository。 */
@Repository
public interface AiActivityLogJpaRepository extends JpaRepository<AiActivityLogJpaEntity, String> {

    /** API-AU-002。 */
    @Query("""
            select a from AiActivityLogJpaEntity a
             where a.occurredAt >= :from and a.occurredAt < :to
               and (:actorUserId is null or a.actorUserId = :actorUserId)
               and (:toolName is null or a.toolName = :toolName)
               and (:actionType is null or a.actionType = :actionType)
             order by a.occurredAt desc
            """)
    Page<AiActivityLogJpaEntity> search(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("actorUserId") String actorUserId,
            @Param("toolName") String toolName,
            @Param("actionType") String actionType,
            Pageable pageable);
}
