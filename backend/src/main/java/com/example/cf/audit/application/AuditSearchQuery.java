package com.example.cf.audit.application;

import com.example.cf.shared.kernel.PageResult;

import java.time.Instant;
import java.util.Map;

/**
 * API-AU-001/002 監査ログ・AI利用記録検索（詳細設計 §6.13、基本設計 §6.6）。
 * 認可はADMIN/AUDITORに限定する（Web層のSecurity設定＋Controllerで検証）。
 */
public interface AuditSearchQuery {

    /** API-AU-001。from/toは呼出し側で最大31日以内であることを検証済みとする。 */
    PageResult<AuditLogItem> searchAuditLogs(
            Instant from,
            Instant to,
            String actorUserId,
            String action,
            String resourceType,
            String resourceId,
            int page,
            int size);

    /** API-AU-002。 */
    PageResult<AiActivityLogItem> searchAiActivities(
            Instant from,
            Instant to,
            String actorUserId,
            String toolName,
            String actionType,
            int page,
            int size);

    record AuditLogItem(
            String auditId,
            Instant occurredAt,
            String actorUserId,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String correlationId,
            Map<String, Object> detail) {
    }

    record AiActivityLogItem(
            String aiActivityId,
            Instant occurredAt,
            String actorUserId,
            String toolName,
            String taskId,
            String actionType,
            String repository,
            String result,
            String approvedBy) {
    }
}
