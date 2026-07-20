package com.example.cf.shared.kernel.money

/**
 * 円金額Value Object（詳細設計 §3.1）。
 * - 通貨はJPY固定、1円単位の整数で保持する（浮動小数点禁止、基本設計 §4.5）。
 * - 0以上、上限 [MAX_AMOUNT]。
 */
@JvmInline
value class Money(val amount: Long) : Comparable<Money> {

    init {
        require(amount >= 0) { "Money must not be negative: $amount" }
        require(amount <= MAX_AMOUNT) { "Money exceeds upper limit: $amount" }
    }

    operator fun plus(other: Money): Money = Money(Math.addExact(amount, other.amount))

    operator fun minus(other: Money): Money {
        require(amount >= other.amount) { "Money subtraction result must not be negative" }
        return Money(amount - other.amount)
    }

    operator fun times(quantity: Int): Money {
        require(quantity >= 0) { "quantity must not be negative: $quantity" }
        return Money(Math.multiplyExact(amount, quantity.toLong()))
    }

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    companion object {
        /** 通貨単位上の上限（1,000億円）。業務個別の上限は各ドメインで別途検証する。 */
        const val MAX_AMOUNT: Long = 100_000_000_000L

        val ZERO = Money(0)

        fun of(amount: Long): Money = Money(amount)
    }
}
