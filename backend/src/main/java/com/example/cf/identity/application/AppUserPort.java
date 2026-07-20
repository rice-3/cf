package com.example.cf.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * app_user 永続化Port（詳細設計 §2.4: domain/application → adapter.out はPort経由）。
 * 実装は {@code identity.adapter.out.persistence.AppUserRepository}。
 */
public interface AppUserPort {

    Optional<AppUserRecord> findById(String userId);

    Optional<AppUserRecord> findByCognitoSubject(String cognitoSubject);

    /** 大文字小文字を無視した重複確認（uq_app_user_email_lower、§8.2）。自分自身は除外する。 */
    boolean existsByEmailExcluding(String email, String excludeUserId);

    /** プロフィール更新（API-US-002）。条件付きUPDATEが0件なら競合または不在。 */
    int updateProfile(String userId, String displayName, String email, long expectedVersion, Instant now);

    /** 状態変更（API-AD-003 会員停止等）。 */
    int updateStatus(String userId, String status, long expectedVersion, Instant now);

    /** ロールのみ変更する場合の楽観ロック用バージョン更新（API-AD-002。roleはuser_role側で管理）。 */
    int touchVersion(String userId, long expectedVersion, Instant now);

    void insert(
            String userId, String cognitoSubject, String email, String displayName, String status, Instant now);

    /** API-AD-001 会員検索。keyword/statusはいずれも任意。 */
    SearchResult search(String keyword, String status, int page, int size);

    record SearchResult(List<AppUserRecord> items, long totalElements) {
    }
}
