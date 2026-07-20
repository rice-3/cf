package com.example.cf.funding.application

import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.id.SupportId

/**
 * Fundingコンテキスト公開契約（基本設計 §4.1）。
 * Paymentコンテキストは決済結果をこのPort経由でのみSupportへ反映する。
 * Support集約・Repositoryへは直接アクセスさせない（§4.2）。
 */
interface SupportPaymentResultPort {

    /** 決済成功をSupportへ反映する（PAYMENT_PENDING/AUTHORIZED → CONFIRMED）。 */
    fun confirmBySupportId(supportId: SupportId, audit: AuditContext)

    /** 決済失敗をSupportへ反映する（→ PAYMENT_FAILED）。 */
    fun failBySupportId(supportId: SupportId, audit: AuditContext)
}
