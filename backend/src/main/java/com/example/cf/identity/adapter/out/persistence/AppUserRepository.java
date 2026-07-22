package com.example.cf.identity.adapter.out.persistence;

import com.example.cf.identity.application.AppUserPort;
import com.example.cf.identity.application.AppUserRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * app_user テーブルへのアクセス（詳細設計 §8.2）。
 * 更新は条件付きUPDATE（WHERE version = :expected）で楽観ロックを実現する（§8.22）。
 */
@Component
public class AppUserRepository implements AppUserPort {

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AppUserRecord> ROW_MAPPER = (rs, rowNum) -> new AppUserRecord(rs.getString("user_id"),
            rs.getString("cognito_subject"), rs.getString("email"), rs.getString("display_name"), rs.getString("status"),
            rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    @Override
    public Optional<AppUserRecord> findById(String userId) {
        return jdbcTemplate.query("select * from app_user where user_id = ?", ROW_MAPPER, userId).stream().findFirst();
    }

    @Override
    public Optional<AppUserRecord> findByCognitoSubject(String cognitoSubject) {
        return jdbcTemplate.query("select * from app_user where cognito_subject = ?", ROW_MAPPER, cognitoSubject).stream().findFirst();
    }

    @Override
    public boolean existsByEmailExcluding(String email, String excludeUserId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from app_user where lower(email) = lower(?) and user_id <> ?",
                Integer.class, email, excludeUserId);
        return count != null && count > 0;
    }

    @Override
    public int updateProfile(String userId, String displayName, String email, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update app_user
                   set display_name = ?, email = ?, version = version + 1, updated_at = ?
                 where user_id = ? and version = ?
                """, displayName, email, Timestamp.from(now), userId, expectedVersion);
    }

    @Override
    public int updateStatus(String userId, String status, long expectedVersion, Instant now) {
        return jdbcTemplate.update(
                "update app_user set status = ?, version = version + 1, updated_at = ? where user_id = ? and version = ?", status,
                Timestamp.from(now), userId, expectedVersion);
    }

    @Override
    public int touchVersion(String userId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("update app_user set version = version + 1, updated_at = ? where user_id = ? and version = ?",
                Timestamp.from(now), userId, expectedVersion);
    }

    @Override
    public void insert(String userId, String cognitoSubject, String email, String displayName, String status, Instant now) {
        jdbcTemplate.update("""
                insert into app_user
                    (user_id, cognito_subject, email, display_name, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, 0, ?, ?)
                """, userId, cognitoSubject, email, displayName, status, Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public SearchResult search(String keyword, String status, int page, int size) {
        String likePattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        List<Object> args = new java.util.ArrayList<>();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (likePattern != null) {
            where.append(" and (email ilike ? or display_name ilike ?)");
            args.add(likePattern);
            args.add(likePattern);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status = ?");
            args.add(status);
        }

        Long total = jdbcTemplate.queryForObject("select count(*) from app_user" + where, Long.class, args.toArray());

        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<AppUserRecord> items = jdbcTemplate.query("select * from app_user" + where + " order by created_at desc limit ? offset ?",
                ROW_MAPPER, pageArgs.toArray());
        return new SearchResult(items, total == null ? 0 : total);
    }
}
