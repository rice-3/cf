package com.example.cf.audit.adapter.in.batch;

import com.example.cf.audit.application.AuditArchivePort;
import com.example.cf.shared.batch.BatchProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BAT-009 監査アーカイブ（基本設計 §8.1、月次）。
 *
 * <p>保持期限（§7.7: 監査ログ3年、AI利用記録1年）を超えたデータをアーカイブ出力し、
 * 出力件数を検証してから削除する。出力に失敗した場合は削除しない。</p>
 */
@Component
public class AuditArchiveBatch {

    private static final Logger log = LoggerFactory.getLogger(AuditArchiveBatch.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditArchivePort archivePort;
    private final BatchProperties properties;
    private final Clock clock;

    public AuditArchiveBatch(JdbcTemplate jdbcTemplate, AuditArchivePort archivePort, BatchProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.archivePort = archivePort;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${cf.batch.audit-archive-cron:0 0 4 1 * *}")
    @SchedulerLock(name = "BAT-009-audit-archive", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void archive() {
        if (!properties.getEnabled()) {
            return;
        }
        try {
            runBatch();
        } catch (RuntimeException e) {
            log.error("BAT-009 audit archive batch failed", e);
        }
    }

    /** @return アーカイブして削除した件数 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int runBatch() {
        Instant now = clock.instant();
        int archived = archiveTable("audit_log", "audit_id", "occurred_at", now.minus(properties.getAuditRetentionDays(), ChronoUnit.DAYS));
        archived += archiveTable("ai_activity_log", "ai_activity_id", "occurred_at",
                now.minus(properties.getAiActivityRetentionDays(), ChronoUnit.DAYS));
        return archived;
    }

    private int archiveTable(String table, String idColumn, String timeColumn, Instant threshold) {
        List<Map<String, Object>> rows = jdbcTemplate
                .queryForList("select * from " + table + " where " + timeColumn + " < ? order by " + timeColumn, Timestamp.from(threshold));
        if (rows.isEmpty()) {
            return 0;
        }

        String archiveName = table + "_until_" + threshold;
        String hash = archivePort.archive(archiveName, rows);
        if (hash == null || hash.isBlank()) {
            // 出力を確認できない場合は削除しない（§9 件数・hash検証）
            log.error("BAT-009 archive returned no hash for {}; skipped deletion", archiveName);
            return 0;
        }

        int deleted = jdbcTemplate.update("delete from " + table + " where " + timeColumn + " < ?", Timestamp.from(threshold));
        if (deleted != rows.size()) {
            // 件数不一致はロールバックして次回に持ち越す
            throw new IllegalStateException(
                    "BAT-009 row count mismatch for " + table + ": archived=" + rows.size() + " deleted=" + deleted);
        }
        log.info("BAT-009 archived and deleted {} rows from {}", deleted, table);
        return deleted;
    }
}
