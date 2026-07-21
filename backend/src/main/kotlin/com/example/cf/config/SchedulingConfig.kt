package com.example.cf.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * スケジューリングとバッチ多重起動防止（基本設計 §8.1/§8.3、ADR-0003）。
 *
 * - `@EnableScheduling`: 定期バッチの起動。testプロファイルでは有効化しないため、
 *   `@Scheduled` メソッドは自動起動しない（結合テストはバッチ処理を直接呼び出す）。
 * - `@EnableSchedulerLock` + ShedLock: 複数インスタンス運用時に同一ジョブが同時起動しないよう、
 *   DBの `shedlock` テーブルでロックする（§8.3「分散ロック」）。
 *   `defaultLockAtMostFor` はインスタンス異常終了時のロック最大保持時間の既定値。
 *
 * BAT-006 Outbox配送は競合コンシューマ設計（FOR UPDATE SKIP LOCKED）で並列配送が正しいため、
 * ShedLockの対象外とする（ADR-0003）。
 */
@Configuration
@Profile("!test")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider = JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(JdbcTemplate(dataSource))
            // アプリとDBの時計ずれを避け、DB時刻でロック期限を判定する
            .usingDbTime()
            .build(),
    )
}
