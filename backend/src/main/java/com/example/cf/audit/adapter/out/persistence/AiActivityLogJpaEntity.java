package com.example.cf.audit.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * ai_activity_log テーブル（詳細設計 §8.20）。
 * 記録側（AiActivityRecordPort実装）は本工程の対象外。CI/AIツール連携整備時に追加する。
 */
@Entity
@Table(name = "ai_activity_log")
public class AiActivityLogJpaEntity {

    @Id
    @Column(name = "ai_activity_id", length = 26)
    private String aiActivityId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_user_id", length = 26, nullable = false)
    private String actorUserId;

    @Column(name = "tool_name", length = 100, nullable = false)
    private String toolName;

    @Column(name = "task_id", length = 100)
    private String taskId;

    @Column(name = "action_type", length = 50, nullable = false)
    private String actionType;

    @Column(name = "repository", length = 200, nullable = false)
    private String repository;

    @Column(name = "result", length = 30, nullable = false)
    private String result;

    @Column(name = "approved_by", length = 26)
    private String approvedBy;

    protected AiActivityLogJpaEntity() {
        // for JPA
    }

    public String getAiActivityId() {
        return aiActivityId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getActionType() {
        return actionType;
    }

    public String getRepository() {
        return repository;
    }

    public String getResult() {
        return result;
    }

    public String getApprovedBy() {
        return approvedBy;
    }
}
