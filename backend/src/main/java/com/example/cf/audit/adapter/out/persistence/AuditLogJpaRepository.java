package com.example.cf.audit.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** audit_log用Spring Data Repository。 */
@Repository
public interface AuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, String> {

    /** API-AU-001（詳細設計 §6.13）。actionは前方一致不可＝完全一致のみ許可する。 */
    @Query("""
            select a from AuditLogJpaEntity a
             where a.occurredAt >= :from and a.occurredAt < :to
               and (:actorUserId is null or a.actorUserId = :actorUserId)
               and (:action is null or a.action = :action)
               and (:resourceType is null or a.resourceType = :resourceType)
               and (:resourceId is null or a.resourceId = :resourceId)
             order by a.occurredAt desc
            """)
    Page<AuditLogJpaEntity> search(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("actorUserId") String actorUserId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            Pageable pageable);
}
