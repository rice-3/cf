package com.example.cf.audit.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * audit_log テーブル（詳細設計 §8.19）。追記専用であり更新APIを提供しない（§11.3）。
 */
@Entity
@Table(name = "audit_log")
public class AuditLogJpaEntity {

    @Id
    @Column(name = "audit_id", length = 26)
    private String auditId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id", length = 26)
    private String actorUserId;

    @Column(name = "action", length = 100, nullable = false)
    private String action;

    @Column(name = "resource_type", length = 100, nullable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Column(name = "result", length = 30, nullable = false)
    private String result;

    @Column(name = "correlation_id", length = 64, nullable = false)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false)
    private Map<String, Object> detail = new HashMap<>();

    @Column(name = "client_ip_hash", length = 64)
    private String clientIpHash;

    protected AuditLogJpaEntity() {
        // for JPA
    }

    public AuditLogJpaEntity(
            String auditId,
            Instant occurredAt,
            String actorUserId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String correlationId,
            Map<String, Object> detail,
            String clientIpHash) {
        this.auditId = auditId;
        this.occurredAt = occurredAt;
        this.actorUserId = actorUserId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.result = result;
        this.correlationId = correlationId;
        this.detail = detail != null ? detail : new HashMap<>();
        this.clientIpHash = clientIpHash;
    }

    public String getAuditId() {
        return auditId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getResult() {
        return result;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public String getClientIpHash() {
        return clientIpHash;
    }
}
