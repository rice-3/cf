package com.example.cf.project.application.query

import com.example.cf.project.domain.model.FundingType
import com.example.cf.project.domain.model.ProjectStatus
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/** 一覧表示用Read Model（CQRS-lite、技術選定書 §17.3）。 */
data class ProjectListItem(
    val projectId: String,
    val title: String,
    val summary: String,
    val targetAmount: Long,
    val fundingType: FundingType,
    val startAt: Instant,
    val endAt: Instant,
    val status: ProjectStatus,
    val updatedAt: Instant,
)

data class RewardPlanView(
    val rewardPlanId: String,
    val name: String,
    val description: String,
    val unitAmount: Long,
    val quantityLimit: Int?,
    val remainingQuantity: Int?,
    val displayOrder: Int,
)

data class ProjectDetailView(
    val projectId: String,
    val ownerUserId: String,
    val title: String,
    val summary: String,
    val body: String,
    val targetAmount: Long,
    val fundingType: FundingType,
    val startAt: Instant,
    val endAt: Instant,
    val status: ProjectStatus,
    val mainFileId: String?,
    val rewardPlans: List<RewardPlanView>,
    val version: Long,
    val updatedAt: Instant,
)

/**
 * 支援可否判定に必要なProject参照値（§4.8 FundingEligibilityPolicyの入力）。
 * Fundingコンテキストはproject表を直接参照せず、この値のみを受け取る。
 */
data class ProjectSupportabilityView(
    val projectId: String,
    val status: ProjectStatus,
    val startAt: Instant,
    val endAt: Instant,
)

/** 支援明細算出に必要なリターン参照値（§5.3 数量・金額検証の入力）。 */
data class RewardPlanReference(
    val rewardPlanId: String,
    val projectId: String,
    val unitAmount: Long,
    val quantityLimit: Int?,
    val reservedQuantity: Int,
    val version: Long,
)

/**
 * Projectコンテキスト公開契約（基本設計 §4.1 ProjectReferenceQuery）。
 * 他コンテキストはProjectのテーブルを直接参照せず、本Query経由で参照する。
 */
interface ProjectReferenceQuery {
    /** プロジェクトIDからタイトルを一括取得する。存在しないIDは結果に含まれない。 */
    fun findTitles(projectIds: Collection<String>): Map<String, String>

    /** 支援可否判定用の参照値を取得する（API-FD-001）。 */
    fun findSupportability(projectId: ProjectId): ProjectSupportabilityView?

    /** リターン参照値を取得する。他プロジェクトのリターン混入検証にも使用する。 */
    fun findRewardPlan(rewardPlanId: String): RewardPlanReference?

    /**
     * リターン数量を条件付きUPDATEで予約する（詳細設計 §4.3.1）。
     * 在庫不足・競合により更新0件の場合はfalseを返す。
     */
    fun reserveRewardQuantity(rewardPlanId: String, quantity: Int, expectedVersion: Long): Boolean
}

/**
 * 参照系Query Port（API-PJ-001/002、SCR-010/011/020）。
 * 実装は adapter.out.persistence に置く。
 */
interface ProjectSearchQuery {

    /** 公開中プロジェクト検索（API-PJ-001）。 */
    fun searchPublished(keyword: String?, page: Int, size: Int): PageResult<ProjectListItem>

    /** 起案者自身のプロジェクト一覧（SCR-020）。 */
    fun listByOwner(ownerUserId: UserId): List<ProjectListItem>

    /** 詳細取得（API-PJ-002）。アクセス可否はUseCase/Controllerで判定する。 */
    fun findDetail(projectId: ProjectId): ProjectDetailView?
}
