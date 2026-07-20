package com.example.cf.funding.application

import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.id.ProjectId
import com.example.cf.shared.kernel.id.SupportId

/** 返金対象の支援（BAT-003が使用）。 */
data class RefundTarget(
    val supportId: String,
    val paymentId: String,
    val amount: Long,
    val supporterUserId: String,
)

/**
 * Fundingコンテキスト公開契約（基本設計 §4.1）。
 * 他コンテキストは support テーブルを直接参照せず、本Query経由で参照する。
 */
interface SupportReferenceQuery {

    /**
     * 決済確定済み（PAID）支援の合計額。BAT-002 募集終了処理の成立判定に使用する。
     * 未確定・失敗・取消済みの支援は含めない。
     */
    fun sumPaidAmount(projectId: ProjectId): Long

    /** 返金対象（PAIDまたはCANCEL_REQUESTED）の支援一覧。BAT-003が使用する。 */
    fun findRefundTargets(projectId: ProjectId): List<RefundTarget>

    /** 単一支援の返金対象情報。返金不可の状態・Payment未紐付けの場合はnull（API-RF-001）。 */
    fun findRefundTarget(supportId: SupportId): RefundTarget?

    /** 支援者のUserId。通知の宛先解決に使用する。 */
    fun findSupporterUserId(supportId: SupportId): String?
}

/**
 * Fundingコンテキスト公開契約: 返金進行に伴うSupport状態の更新。
 * Support集約・Repositoryは他コンテキストへ公開せず、本Port経由でのみ遷移させる（§4.2）。
 */
interface SupportRefundPort {

    /** 返金要求（→ REFUND_REQUESTED）。BAT-003が使用する。 */
    fun requireRefund(supportId: SupportId, audit: AuditContext)

    /** 返金処理開始（→ REFUNDING）。BAT-004が使用する。 */
    fun startRefund(supportId: SupportId, audit: AuditContext)

    /** 返金完了（→ REFUNDED）。 */
    fun markRefunded(supportId: SupportId, audit: AuditContext)

    /** 返金失敗（→ REFUND_FAILED）。 */
    fun failRefund(supportId: SupportId, audit: AuditContext)
}
