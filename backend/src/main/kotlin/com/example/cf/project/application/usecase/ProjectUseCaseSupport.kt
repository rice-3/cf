package com.example.cf.project.application.usecase

import com.example.cf.project.application.command.RewardPlanCommand
import com.example.cf.project.domain.model.FundingCondition
import com.example.cf.project.domain.model.ProjectBody
import com.example.cf.project.domain.model.ProjectStatus
import com.example.cf.project.domain.model.ProjectSummary
import com.example.cf.project.domain.model.ProjectTitle
import com.example.cf.project.domain.model.RewardPlan
import com.example.cf.shared.kernel.error.ValidationException
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.id.UlidGenerator
import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.kernel.time.DateRange
import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.project.domain.model.FundingType
import java.time.Instant

/** ドメインVO生成時のIllegalArgumentExceptionを400 VALIDATION_ERRORへ変換する。 */
inline fun <T> mapDomainValidation(block: () -> T): T =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        throw ValidationException(message = e.message ?: "validation failed")
    }

fun buildFundingCondition(
    targetAmount: Long,
    fundingType: FundingType,
    startAt: Instant,
    endAt: Instant,
): FundingCondition = mapDomainValidation {
    FundingCondition(Money.of(targetAmount), fundingType, DateRange(startAt, endAt))
}

fun buildTitle(value: String) = mapDomainValidation { ProjectTitle(value) }
fun buildSummary(value: String) = mapDomainValidation { ProjectSummary(value) }
fun buildBody(value: String) = mapDomainValidation { ProjectBody(value) }

fun buildRewardPlans(commands: List<RewardPlanCommand>, generator: UlidGenerator): List<RewardPlan> =
    mapDomainValidation {
        commands.map { cmd ->
            RewardPlan.create(
                id = RewardPlanId.newId(generator),
                name = cmd.name,
                description = cmd.description,
                unitAmount = Money.of(cmd.unitAmount),
                quantityLimit = cmd.quantityLimit,
                displayOrder = cmd.displayOrder,
            )
        }
    }

// ---- 結果DTO ----------------------------------------------------------------

data class CreateProjectResult(val projectId: ProjectId, val status: ProjectStatus)

data class UpdateProjectResult(
    val projectId: ProjectId,
    val status: ProjectStatus,
    val version: Version,
    val updatedAt: Instant,
)

data class SubmitProjectForReviewResult(
    val reviewId: com.example.cf.shared.kernel.id.ReviewId,
    val projectStatus: ProjectStatus,
    val submittedAt: Instant,
)

data class CancelProjectResult(val projectId: ProjectId, val status: ProjectStatus)
