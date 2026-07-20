package com.example.cf.funding.domain.repository

import com.example.cf.funding.domain.model.Support
import com.example.cf.shared.kernel.id.SupportId

interface SupportRepository {
    fun findById(id: SupportId): Support?

    /** 状態変更用に悲観ロックで取得する（§5.5）。 */
    fun findByIdForUpdate(id: SupportId): Support?

    fun save(support: Support)
}
