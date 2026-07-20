package com.example.cf.identity.application;

import java.time.Instant;
import java.util.List;

/**
 * user_role 永続化Port（詳細設計 §2.4）。
 * 実装は {@code identity.adapter.out.persistence.UserRoleRepository}。
 */
public interface UserRolePort {

    List<String> findRoles(String userId);

    /** 付与可能なロール一覧（role.assignable = true、詳細設計 §8.3）。GUEST/AI_AGENTは対象外。 */
    List<String> findAssignableRoleCodes();

    /** ロールを全置換する。既存の割当は削除してから再作成する。 */
    void replaceRoles(String userId, List<String> roles, String assignedBy, Instant now);

    void insertRole(String userId, String role, String assignedBy, Instant now);
}
