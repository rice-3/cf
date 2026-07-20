package com.example.cf.funding.application

import com.example.cf.funding.domain.model.SupportStatus
import com.example.cf.shared.kernel.PageResult
import com.example.cf.shared.kernel.id.SupportId
import com.example.cf.shared.kernel.id.UserId
import java.time.Instant

/** 支援一覧項目（SCR-051 支援履歴、API-FD-002）。 */
data class SupportListItem(
    val supportId: String,
    val projectId: String,
    val projectTitle: String,
    val amount: Long,
    val status: SupportStatus,
    val paymentStatus: String?,
    val createdAt: Instant,
)

data class SupportItemView(
    val rewardPlanId: String?,
    val quantity: Int,
    val unitAmount: Long,
    val amount: Long,
)

data class SupportDetailView(
    val supportId: String,
    val projectId: String,
    val projectTitle: String,
    val supporterUserId: String,
    val amount: Long,
    val status: SupportStatus,
    val paymentStatus: String?,
    val contactEmail: String,
    val items: List<SupportItemView>,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * 参照系Query Port（API-FD-002/003、SCR-051）。
 */
interface SupportSearchQuery {

    /** 自分の支援一覧（API-FD-002）。 */
    fun listBySupporter(supporterUserId: UserId, page: Int, size: Int): PageResult<SupportListItem>

    /** 支援詳細（API-FD-003）。所有権判定は呼出し側で行う。 */
    fun findDetail(supportId: SupportId): SupportDetailView?
}
