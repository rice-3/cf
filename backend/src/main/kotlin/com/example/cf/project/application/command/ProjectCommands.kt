package com.example.cf.project.application.command

import com.example.cf.project.domain.model.FundingType
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.FileId
import com.example.cf.shared.kernel.id.ProjectId
import java.time.Instant

/** リターンプラン入力（作成・更新共通）。 */
data class RewardPlanCommand(
    val name: String,
    val description: String,
    val unitAmount: Long,
    val quantityLimit: Int?,
    val displayOrder: Int,
)

/** UC-PJ-001 プロジェクト下書き作成。 */
data class CreateProjectCommand(
    val title: String,
    val summary: String,
    val body: String,
    val targetAmount: Long,
    val fundingType: FundingType,
    val startAt: Instant,
    val endAt: Instant,
    val mainFileId: FileId?,
    val rewardPlans: List<RewardPlanCommand>,
)

/** UC-PJ-002 プロジェクト下書き更新。 */
data class UpdateProjectCommand(
    val projectId: ProjectId,
    val expectedVersion: Version,
    val title: String,
    val summary: String,
    val body: String,
    val targetAmount: Long,
    val fundingType: FundingType,
    val startAt: Instant,
    val endAt: Instant,
    val mainFileId: FileId?,
    val rewardPlans: List<RewardPlanCommand>,
)

/** UC-PJ-003 審査申請。 */
data class SubmitProjectForReviewCommand(
    val projectId: ProjectId,
    val expectedVersion: Version,
    /** SCR-023の確認事項コード。全件確認済みであること。 */
    val confirmations: Set<String>,
)

/** API-PJ-006 取消。 */
data class CancelProjectCommand(
    val projectId: ProjectId,
    val expectedVersion: Version,
    val reason: String?,
)
