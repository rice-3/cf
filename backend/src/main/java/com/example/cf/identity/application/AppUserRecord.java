package com.example.cf.identity.application;

import java.time.Instant;

/**
 * app_user テーブル（詳細設計 §8.2）の読み取り結果。
 *
 * <p>Identityは単純CRUDが中心のため、リッチな集約ではなくPort越しの素朴なレコード表現とする
 * （要件定義 §4.4-8、基本設計 A-10: DDDの過剰適用を避ける）。</p>
 */
public record AppUserRecord(
        String userId,
        String cognitoSubject,
        String email,
        String displayName,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
