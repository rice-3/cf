package com.example.cf.audit.adapter.out.persistence;

import com.example.cf.audit.application.AuditSearchQuery;
import com.example.cf.shared.kernel.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * API-AU-001/002 の永続化Adapter（詳細設計 §6.13）。
 * open-in-view無効のため参照系は読み取り専用トランザクションで実行する。
 */
@Component
public class AuditSearchPersistenceAdapter implements AuditSearchQuery {

    private final AuditLogJpaRepository auditLogRepository;
    private final AiActivityLogJpaRepository aiActivityLogRepository;

    public AuditSearchPersistenceAdapter(AuditLogJpaRepository auditLogRepository, AiActivityLogJpaRepository aiActivityLogRepository) {
        this.auditLogRepository = auditLogRepository;
        this.aiActivityLogRepository = aiActivityLogRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AuditLogItem> searchAuditLogs(Instant from, Instant to, String actorUserId, String action, String resourceType,
            String resourceId, int page, int size) {
        Page<AuditLogJpaEntity> result = auditLogRepository.search(from, to, actorUserId, action, resourceType, resourceId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt")));
        List<AuditLogItem> items = result.getContent().stream()
                .map(e -> new AuditLogItem(e.getAuditId(), e.getOccurredAt(), e.getActorUserId(), e.getAction(), e.getResourceType(),
                        e.getResourceId(), e.getResult(), e.getCorrelationId(), e.getDetail()))
                .toList();
        return toPageResult(items, page, size, result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<AiActivityLogItem> searchAiActivities(Instant from, Instant to, String actorUserId, String toolName,
            String actionType, int page, int size) {
        Page<AiActivityLogJpaEntity> result = aiActivityLogRepository.search(from, to, actorUserId, toolName, actionType,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt")));
        List<AiActivityLogItem> items = result.getContent().stream().map(e -> new AiActivityLogItem(e.getAiActivityId(), e.getOccurredAt(),
                e.getActorUserId(), e.getToolName(), e.getTaskId(), e.getActionType(), e.getRepository(), e.getResult(), e.getApprovedBy()))
                .toList();
        return toPageResult(items, page, size, result.getTotalElements());
    }

    private <T> PageResult<T> toPageResult(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResult<>(items, page, size, totalElements, totalPages);
    }
}
