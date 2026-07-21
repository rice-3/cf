package com.example.cf.funding.application

import com.example.cf.funding.domain.model.SupportStatus
import com.example.cf.shared.kernel.PageResult
import java.time.Instant

/** 運用者向け支援一覧項目（SCR-060 支援管理、運用者横断検索）。 */
data class OperationsSupportListItem(
    val supportId: String,
    val projectId: String,
    val projectTitle: String,
    val supporterUserId: String,
    val amount: Long,
    val status: SupportStatus,
    val paymentId: String?,
    val paymentStatus: String?,
    val createdAt: Instant,
)

/**
 * 運用者向け支援検索Query（OPERATOR/ADMIN、全利用者横断）。
 * 支援者本人向けの [SupportSearchQuery.listBySupporter] とは別に、運用者が状態・プロジェクトで
 * 横断検索するための公開契約（基本設計 §4.1）。
 */
interface OperationsSupportSearchQuery {
    fun search(
        status: SupportStatus?,
        projectId: String?,
        page: Int,
        size: Int,
    ): PageResult<OperationsSupportListItem>
}
