package com.example.cf.shared.idempotency

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * BAT-010 冪等記録削除。失効済みの idempotency_record を物理削除する。
 *
 * 基本設計 §8.1 にバッチ項目は無いが、詳細設計 §9 BAT-008相当の記載があり、
 * `idempotency_record` は失効（expires_at超過）後も物理削除されず単調増加するため追加した
 * （残タスク §2 の要確認事項に対する対応。ADR相当の判断は docs に記録）。
 */
interface IdempotencyCleanupUseCase {
    /** @return 削除件数 */
    fun execute(limit: Int): Int
}

@Service
class IdempotencyCleanupService(
    private val idempotencyPort: IdempotencyPort,
) : IdempotencyCleanupUseCase {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun execute(limit: Int): Int = idempotencyPort.deleteExpired(limit)
}
