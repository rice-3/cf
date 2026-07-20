package com.example.cf.audit.adapter.out.persistence;

import com.example.cf.audit.application.AuditRecordPort;
import com.example.cf.shared.kernel.id.UlidGenerator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * 監査ログ永続化Adapter（Java実装、詳細設計 §2.2 言語配置方針）。
 * 呼出し元の業務トランザクションへ参加し、業務更新と同時にコミットする（§5.1）。
 */
@Component
public class AuditPersistenceAdapter implements AuditRecordPort {

    private final AuditLogJpaRepository repository;
    private final UlidGenerator idGenerator;
    private final Clock clock;

    public AuditPersistenceAdapter(AuditLogJpaRepository repository, UlidGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    public void record(
            String actorUserId,
            String correlationId,
            String source,
            String clientIpHash,
            String action,
            String resourceType,
            String resourceId,
            String result) {
        record(actorUserId, correlationId, source, clientIpHash, action, resourceType, resourceId, result, null);
    }

    @Override
    public void record(
            String actorUserId,
            String correlationId,
            String source,
            String clientIpHash,
            String action,
            String resourceType,
            String resourceId,
            String result,
            Map<String, Object> detail) {
        Map<String, Object> merged = new HashMap<>();
        merged.put("source", source);
        if (detail != null) {
            merged.putAll(detail);
        }
        repository.save(new AuditLogJpaEntity(
                idGenerator.next(),
                clock.instant(),
                actorUserId,
                action,
                resourceType,
                resourceId,
                result,
                correlationId,
                merged,
                clientIpHash
        ));
    }
}
