package com.example.cf.project.domain.service

import com.example.cf.project.domain.model.Project
import com.example.cf.shared.kernel.error.BusinessRuleViolationException
import java.time.Instant

/**
 * 審査申請可能性の総合判定（詳細設計 §4.8）。
 * 違反一覧を返し、UseCaseは [validateOrThrow] で PROJECT_INCOMPLETE(422) へ変換する。
 */
class ProjectSubmissionPolicy {

    fun validate(project: Project, now: Instant): List<String> {
        val violations = mutableListOf<String>()

        if (!project.status.editable) {
            violations += "PROJECT_INVALID_STATE: status=${project.status}"
        }
        if (project.mainFileId == null) {
            violations += "MAIN_IMAGE_REQUIRED"
        }
        if (project.rewardPlans.isEmpty()) {
            violations += "REWARD_PLAN_REQUIRED"
        }
        if (!project.fundingCondition.period.start.isAfter(now)) {
            violations += "FUNDING_START_MUST_BE_FUTURE"
        }
        return violations
    }

    fun validateOrThrow(project: Project, now: Instant) {
        val violations = validate(project, now)
        if (violations.isNotEmpty()) {
            throw BusinessRuleViolationException(
                errorCode = "PROJECT_INCOMPLETE",
                message = "Project ${project.id.value} does not satisfy submission requirements",
                violations = violations,
            )
        }
    }
}
