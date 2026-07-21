package com.example.cf.payment.application

import com.example.cf.payment.domain.model.RefundStatus
import com.example.cf.shared.kernel.PageResult
import java.time.Instant

/** 運用者向け返金一覧項目（SCR-061 返金管理、運用者横断検索）。 */
data class RefundListItem(
    val refundId: String,
    val supportId: String,
    val paymentId: String,
    val amount: Long,
    val reasonCode: String,
    val status: RefundStatus,
    val retryCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * 運用者向け返金検索Query（OPERATOR/ADMIN）。状態で絞り込み、再実行対象の特定に用いる。
 */
interface RefundSearchQuery {
    fun search(status: RefundStatus?, page: Int, size: Int): PageResult<RefundListItem>
}
