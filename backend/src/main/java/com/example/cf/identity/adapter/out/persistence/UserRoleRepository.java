package com.example.cf.identity.adapter.out.persistence;

import com.example.cf.identity.application.UserRolePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * user_role テーブルへのアクセス（詳細設計 §8.4）。
 * ロール変更は「全置換」を基本とする（部分追加・削除APIは設けない、API-AD-002）。
 */
@Component
public class UserRoleRepository implements UserRolePort {

    private final JdbcTemplate jdbcTemplate;

    public UserRoleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> findRoles(String userId) {
        return jdbcTemplate.queryForList(
                "select role_code from user_role where user_id = ? order by role_code", String.class, userId);
    }

    @Override
    public List<String> findAssignableRoleCodes() {
        return jdbcTemplate.queryForList(
                "select role_code from role where assignable = true order by role_code", String.class);
    }

    @Override
    public void replaceRoles(String userId, List<String> roles, String assignedBy, Instant now) {
        jdbcTemplate.update("delete from user_role where user_id = ?", userId);
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.batchUpdate(
                "insert into user_role (user_id, role_code, assigned_at, assigned_by) values (?, ?, ?, ?)",
                roles.stream()
                        .map(role -> new Object[] {userId, role, ts, assignedBy})
                        .toList());
    }

    @Override
    public void insertRole(String userId, String role, String assignedBy, Instant now) {
        jdbcTemplate.update(
                "insert into user_role (user_id, role_code, assigned_at, assigned_by) values (?, ?, ?, ?)",
                userId, role, Timestamp.from(now), assignedBy);
    }
}
