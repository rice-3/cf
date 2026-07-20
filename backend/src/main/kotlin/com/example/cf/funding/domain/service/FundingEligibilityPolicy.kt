package com.example.cf.funding.domain.service

import com.example.cf.shared.kernel.error.InvalidStateException
import java.time.Instant

/** 支援可否判定への入力（Project公開契約から取得した参照値、§4.8）。 */
data class SupportabilityInput(
    val projectStatus: String,
    val fundingStart: Instant,
    val fundingEnd: Instant,
    val supporterActive: Boolean,
)

/**
 * 支援可能性の判定（詳細設計 §4.8 FundingEligibilityPolicy、§5.3）。
 * PUBLISHED、期間内、会員ACTIVEのすべてを満たすこと。
 */
class FundingEligibilityPolicy {

    fun validateOrThrow(input: SupportabilityInput, now: Instant) {
        val violations = mutableListOf<String>()
        if (input.projectStatus != "PUBLISHED") {
            violations += "PROJECT_NOT_PUBLISHED"
        }
        if (now.isBefore(input.fundingStart) || !now.isBefore(input.fundingEnd)) {
            violations += "FUNDING_PERIOD_OUT_OF_RANGE"
        }
        if (!input.supporterActive) {
            violations += "SUPPORTER_NOT_ACTIVE"
        }
        if (violations.isNotEmpty()) {
            throw InvalidStateException(
                "PROJECT_NOT_SUPPORTABLE",
                "Project is not supportable: ${violations.joinToString(",")}",
            )
        }
    }
}
