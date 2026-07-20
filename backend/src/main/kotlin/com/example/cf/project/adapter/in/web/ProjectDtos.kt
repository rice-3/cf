package com.example.cf.project.adapter.`in`.web

import com.example.cf.project.application.command.RewardPlanCommand
import com.example.cf.project.domain.model.FundingType
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/** リターンプラン入力（API-PJ-003/004）。 */
data class RewardPlanRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 100)
    val name: String = "",

    @field:NotBlank
    @field:Size(min = 1, max = 2000)
    val description: String = "",

    @field:Min(1)
    val unitAmount: Long = 0,

    @field:Min(1)
    val quantityLimit: Int? = null,

    @field:Min(0)
    val displayOrder: Int = 0,
) {
    fun toCommand() = RewardPlanCommand(name, description, unitAmount, quantityLimit, displayOrder)
}

/** API-PJ-003 下書き作成Request（詳細設計 §6.3）。 */
data class CreateProjectRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 100)
    val title: String = "",

    @field:NotBlank
    @field:Size(min = 1, max = 300)
    val summary: String = "",

    @field:NotBlank
    @field:Size(min = 1, max = 20_000)
    val body: String = "",

    @field:Min(1_000)
    @field:Max(100_000_000)
    val targetAmount: Long = 0,

    @field:NotNull
    val fundingType: FundingType = FundingType.ALL_OR_NOTHING,

    @field:NotNull
    val startAt: Instant = Instant.EPOCH,

    @field:NotNull
    val endAt: Instant = Instant.EPOCH,

    val mainFileId: String? = null,

    @field:Valid
    @field:Size(max = 100)
    val rewardPlans: List<RewardPlanRequest> = emptyList(),
)

/** API-PJ-004 下書き更新Request（詳細設計 §6.4）。 */
data class UpdateProjectRequest(
    @field:Min(0)
    val expectedVersion: Long = 0,

    @field:NotBlank
    @field:Size(min = 1, max = 100)
    val title: String = "",

    @field:NotBlank
    @field:Size(min = 1, max = 300)
    val summary: String = "",

    @field:NotBlank
    @field:Size(min = 1, max = 20_000)
    val body: String = "",

    @field:Min(1_000)
    @field:Max(100_000_000)
    val targetAmount: Long = 0,

    @field:NotNull
    val fundingType: FundingType = FundingType.ALL_OR_NOTHING,

    @field:NotNull
    val startAt: Instant = Instant.EPOCH,

    @field:NotNull
    val endAt: Instant = Instant.EPOCH,

    val mainFileId: String? = null,

    @field:Valid
    @field:Size(max = 100)
    val rewardPlans: List<RewardPlanRequest> = emptyList(),
)

/** API-PJ-005 審査申請Request（詳細設計 §6.5）。 */
data class SubmitReviewRequest(
    @field:Min(0)
    val expectedVersion: Long = 0,

    @field:NotNull
    val confirmations: List<String> = emptyList(),
)

/** API-PJ-006 取消Request。 */
data class CancelProjectRequest(
    @field:Min(0)
    val expectedVersion: Long = 0,

    @field:Size(max = 2000)
    val reason: String? = null,
)

// ---- Response ---------------------------------------------------------------

data class ProjectCreatedResponse(val projectId: String, val status: String)

data class ProjectUpdatedResponse(
    val projectId: String,
    val status: String,
    val version: Long,
    val updatedAt: Instant,
)

data class SubmitReviewResponse(
    val reviewId: String,
    val projectStatus: String,
    val submittedAt: Instant,
)

data class ProjectCancelledResponse(val projectId: String, val status: String)
