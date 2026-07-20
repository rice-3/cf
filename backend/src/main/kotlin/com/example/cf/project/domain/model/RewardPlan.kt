package com.example.cf.project.domain.model

import com.example.cf.shared.kernel.Version
import com.example.cf.shared.kernel.id.RewardPlanId
import com.example.cf.shared.kernel.money.Money

/**
 * リターンプラン（Project集約内部Entity）。
 * 集約外から直接更新しない（要件定義 §4.4-1）。
 */
class RewardPlan(
    val id: RewardPlanId,
    name: String,
    description: String,
    unitAmount: Money,
    quantityLimit: Int?,
    reservedQuantity: Int,
    displayOrder: Int,
    val version: Version = Version(0),
) {
    var name: String = name
        private set
    var description: String = description
        private set
    var unitAmount: Money = unitAmount
        private set
    var quantityLimit: Int? = quantityLimit
        private set
    var reservedQuantity: Int = reservedQuantity
        private set
    var displayOrder: Int = displayOrder
        private set

    init {
        require(name.length in 1..100) { "reward name must be 1..100 characters" }
        require(description.length in 1..2_000) { "reward description must be 1..2000 characters" }
        require(unitAmount.amount > 0) { "reward unit amount must be positive" }
        quantityLimit?.let { require(it > 0) { "quantity limit must be positive" } }
        require(reservedQuantity >= 0) { "reserved quantity must be >= 0" }
        quantityLimit?.let {
            require(reservedQuantity <= it) { "reserved quantity must not exceed limit" }
        }
    }

    /** 残数。上限なしの場合はnull。 */
    val remainingQuantity: Int? get() = quantityLimit?.let { it - reservedQuantity }

    companion object {
        fun create(
            id: RewardPlanId,
            name: String,
            description: String,
            unitAmount: Money,
            quantityLimit: Int?,
            displayOrder: Int,
        ): RewardPlan = RewardPlan(
            id = id,
            name = name,
            description = description,
            unitAmount = unitAmount,
            quantityLimit = quantityLimit,
            reservedQuantity = 0,
            displayOrder = displayOrder,
        )
    }
}
