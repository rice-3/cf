package com.example.cf.audit.application;

import java.util.Map;

/**
 * 監査記録Port（基本設計 §4.1: Audit公開契約 AuditRecordPort）。
 * Auditコンテキストは他モジュールへこのインターフェースのみを公開する。
 *
 * <p>Identity/Audit系はJava実装とする（詳細設計 §2.2 言語配置方針）。
 * Kotlin側のValue Object（UserId等）はJava境界で素のStringへ明示変換する（§1.3）。
 * Kotlinからは {@code AuditRecordPortExt.kt} の拡張関数でAuditContextを渡せる。</p>
 */
public interface AuditRecordPort {

    /**
     * 重要操作を監査ログへ記録する（詳細設計 §11.5）。
     * 呼出し元のトランザクションに参加し、業務更新と同時にコミットされる（§5.1）。
     *
     * @param actorUserId   実行者の内部UserId（システム起動時はnull）
     * @param correlationId 相関ID
     * @param source        WEB_API / BATCH / SYSTEM
     * @param clientIpHash  クライアントIPのSHA-256ハッシュ（任意）
     * @param action        操作コード（例: PROJECT_SUBMIT_REVIEW）
     * @param resourceType  対象リソース種別（例: Project）
     * @param resourceId    対象リソースID
     * @param result        SUCCESS / FAILURE
     */
    void record(String actorUserId, String correlationId, String source, String clientIpHash, String action, String resourceType,
            String resourceId, String result);

    /**
     * 追加詳細（変更理由・変更後ロール等）を伴う記録（工程9: USER_ROLE_UPDATE/USER_SUSPEND用）。
     * 既定実装は detail を無視して従来の8引数メソッドへ委譲するため、既存呼出し元に影響しない。
     */
    default void record(String actorUserId, String correlationId, String source, String clientIpHash, String action, String resourceType,
            String resourceId, String result, Map<String, Object> detail) {
        record(actorUserId, correlationId, source, clientIpHash, action, resourceType, resourceId, result);
    }
}
