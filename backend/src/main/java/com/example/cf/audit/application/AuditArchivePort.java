package com.example.cf.audit.application;

import java.util.List;
import java.util.Map;

/**
 * 監査データのアーカイブ出力Port（BAT-009、基本設計 §8.1）。
 *
 * <p>本番想定ではS3 Glacier相当へ出力する。出力後に件数とハッシュを検証してから
 * 元データを削除する（詳細設計 §9）。</p>
 */
public interface AuditArchivePort {

    /**
     * アーカイブ対象を出力する。
     *
     * @param archiveName 出力単位の名称（例: audit_log_2026-07）
     * @param rows        出力対象の行
     * @return 出力内容のSHA-256ハッシュ（検証用）
     */
    String archive(String archiveName, List<Map<String, Object>> rows);
}
