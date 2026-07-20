package com.example.cf.payment.application

/**
 * Paymentコンテキスト公開契約（基本設計 §4.1）。
 * 他コンテキストはpayment表を直接参照せず、本Query経由で状態を参照する。
 */
interface PaymentReferenceQuery {

    /** PaymentIDから決済状態を一括取得する。存在しないIDは結果に含まれない。 */
    fun findStatuses(paymentIds: Collection<String>): Map<String, String>
}
