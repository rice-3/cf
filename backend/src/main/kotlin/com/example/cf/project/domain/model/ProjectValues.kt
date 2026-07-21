package com.example.cf.project.domain.model

import com.example.cf.shared.kernel.money.Money
import com.example.cf.shared.kernel.time.DateRange

@JvmInline
value class ProjectTitle(val value: String) {
    init {
        require(value.length in 1..100) { "title must be 1..100 characters" }
    }
}

@JvmInline
value class ProjectSummary(val value: String) {
    init {
        require(value.length in 1..300) { "summary must be 1..300 characters" }
    }
}

@JvmInline
value class ProjectBody(val value: String) {
    init {
        require(value.length in 1..20_000) { "body must be 1..20000 characters" }
    }
}

/** 募集方式（要件確認 10.1-1）。初期値は All-or-Nothing。 */
enum class FundingType { ALL_OR_NOTHING, ALL_IN }

/**
 * 募集条件（金額・方式・期間）。詳細設計 §4.1.3 の不変条件を保持する。
 */
data class FundingCondition(
    val targetAmount: Money,
    val fundingType: FundingType,
    val period: DateRange,
) {
    init {
        require(targetAmount.amount in MIN_TARGET_AMOUNT..MAX_TARGET_AMOUNT) {
            "target amount must be between $MIN_TARGET_AMOUNT and $MAX_TARGET_AMOUNT"
        }
        period.requireMaxDays(MAX_FUNDING_DAYS)
    }

    companion object {
        const val MIN_TARGET_AMOUNT = 1_000L
        const val MAX_TARGET_AMOUNT = 100_000_000L
        const val MAX_FUNDING_DAYS = 180L
    }
}
