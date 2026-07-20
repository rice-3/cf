package com.example.cf.payment.domain.repository

import com.example.cf.payment.domain.model.Payment
import com.example.cf.shared.kernel.id.PaymentId

interface PaymentRepository {
    fun findById(id: PaymentId): Payment?

    /** Webhook処理用に悲観ロックで取得する（§5.4-5）。 */
    fun findByIdForUpdate(id: PaymentId): Payment?

    fun findByProviderPaymentId(provider: String, providerPaymentId: String): Payment?

    /** BAT-007 決済照合の対象（結果不明のまま滞留した決済）。 */
    fun findReconcileTargets(limit: Int): List<PaymentId>

    fun save(payment: Payment)
}
