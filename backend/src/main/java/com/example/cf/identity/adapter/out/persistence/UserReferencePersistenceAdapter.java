package com.example.cf.identity.adapter.out.persistence;

import com.example.cf.identity.application.UserReferenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * app_userテーブル（詳細設計 §8.2）に対するUserReferenceQuery実装。
 */
@Component
public class UserReferencePersistenceAdapter implements UserReferenceQuery {

    private final JdbcTemplate jdbcTemplate;

    public UserReferencePersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String findEmail(String userId) {
        return jdbcTemplate.query(
                        "select email from app_user where user_id = ?",
                        (rs, rowNum) -> rs.getString("email"),
                        userId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean isActive(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from app_user where user_id = ? and status = 'ACTIVE'",
                Integer.class,
                userId);
        return count != null && count > 0;
    }
}
