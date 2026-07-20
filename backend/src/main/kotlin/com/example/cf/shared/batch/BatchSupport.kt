package com.example.cf.shared.batch

import com.example.cf.shared.kernel.AuditContext
import com.example.cf.shared.kernel.AuditSource
import com.example.cf.shared.kernel.id.CorrelationId
import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.UUID

/**
 * バッチ設定（基本設計 §8.1 の周期・件数）。
 * `enabled` はテストで無効化するために使う。
 */
@ConfigurationProperties(prefix = "cf.batch")
data class BatchProperties(
    val enabled: Boolean = true,
    /** BAT-001/002 の1回あたり処理件数（詳細設計 §9: 100件/Tx）。 */
    val projectBatchSize: Int = 100,
    /** BAT-004/005 の1回あたり処理件数。 */
    val workerBatchSize: Int = 50,
    /** BAT-008 ファイル清掃の1回あたり件数（詳細設計 §9: 1000件/回）。 */
    val fileCleanupBatchSize: Int = 1000,
    /** 監査ログの保持期間（基本設計 §7.7: 3年）。 */
    val auditRetentionDays: Long = 365 * 3,
    /** AI利用記録の保持期間（基本設計 §7.7: 1年）。 */
    val aiActivityRetentionDays: Long = 365,
)

/**
 * バッチ実行時の監査コンテキスト（詳細設計 §3.5）。
 * 実行者は存在せず、発生元はBATCHとなる。相関IDは起動ごとに採番する。
 */
fun batchAuditContext(): AuditContext = AuditContext(
    actorUserId = null,
    correlationId = CorrelationId("bat_${UUID.randomUUID()}"),
    source = AuditSource.BATCH,
)
