package com.example.cf.payment.domain.repository

import com.example.cf.payment.domain.model.Refund
import com.example.cf.shared.kernel.id.RefundId
import com.example.cf.shared.kernel.id.SupportId
import java.time.Instant

interface RefundRepository {
    fun findById(id: RefundId): Refund?

    fun findByIdForUpdate(id: RefundId): Refund?

    /** 有効な（FAILED以外の）返金の存在確認。二重返金防止（§6.9）。 */
    fun findActiveBySupportId(supportId: SupportId): Refund?

    /**
     * BAT-004 返金実行の対象を取得する。
     * REQUESTED、または再試行時刻に達したRETRY_WAITを `FOR UPDATE SKIP LOCKED` で取得する（基本設計 §8.3）。
     */
    fun lockExecutableBatch(now: Instant, limit: Int): List<Refund>

    fun save(refund: Refund)
}
