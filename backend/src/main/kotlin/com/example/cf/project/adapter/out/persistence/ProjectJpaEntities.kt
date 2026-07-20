package com.example.cf.project.adapter.out.persistence

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

/**
 * project テーブル（詳細設計 §8.5）。
 * ADR-002: 重要集約はJPA EntityとDomain Modelを分離する。
 */
@Entity
@Table(name = "project")
class ProjectJpaEntity(
    @Id
    @Column(name = "project_id", length = 26)
    var projectId: String = "",

    @Column(name = "owner_user_id", length = 26, nullable = false)
    var ownerUserId: String = "",

    @Column(name = "title", length = 100, nullable = false)
    var title: String = "",

    @Column(name = "summary", length = 300, nullable = false)
    var summary: String = "",

    @Column(name = "body", nullable = false)
    var body: String = "",

    @Column(name = "target_amount", nullable = false)
    var targetAmount: Long = 0,

    @Column(name = "funding_type", length = 30, nullable = false)
    var fundingType: String = "",

    @Column(name = "start_at", nullable = false)
    var startAt: Instant = Instant.EPOCH,

    @Column(name = "end_at", nullable = false)
    var endAt: Instant = Instant.EPOCH,

    @Column(name = "status", length = 30, nullable = false)
    var status: String = "",

    @Column(name = "main_file_id", length = 26)
    var mainFileId: String? = null,

    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,

    @OneToMany(
        mappedBy = "project",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    var rewardPlans: MutableList<RewardPlanJpaEntity> = mutableListOf(),
)

/** reward_plan テーブル（詳細設計 §8.6）。 */
@Entity
@Table(name = "reward_plan")
class RewardPlanJpaEntity(
    @Id
    @Column(name = "reward_plan_id", length = 26)
    var rewardPlanId: String = "",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    var project: ProjectJpaEntity? = null,

    @Column(name = "name", length = 100, nullable = false)
    var name: String = "",

    @Column(name = "description", length = 2000, nullable = false)
    var description: String = "",

    @Column(name = "unit_amount", nullable = false)
    var unitAmount: Long = 0,

    @Column(name = "quantity_limit")
    var quantityLimit: Int? = null,

    @Column(name = "reserved_quantity", nullable = false)
    var reservedQuantity: Int = 0,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

/** project_status_history テーブル（詳細設計 §8.7）。 */
@Entity
@Table(name = "project_status_history")
class ProjectStatusHistoryJpaEntity(
    @Id
    @Column(name = "history_id", length = 26)
    var historyId: String = "",

    @Column(name = "project_id", length = 26, nullable = false)
    var projectId: String = "",

    @Column(name = "from_status", length = 30)
    var fromStatus: String? = null,

    @Column(name = "to_status", length = 30, nullable = false)
    var toStatus: String = "",

    @Column(name = "reason", length = 2000)
    var reason: String? = null,

    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.EPOCH,

    @Column(name = "changed_by", length = 26)
    var changedBy: String? = null,
)
