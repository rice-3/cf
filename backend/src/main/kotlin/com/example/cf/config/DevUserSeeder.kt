package com.example.cf.config

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock

/**
 * 開発用ユーザーの初期投入（local/testプロファイル限定）。
 * 合成データのみを使用する（要件 C-16。実在情報・認証情報をSQLへ直書きしない §14.3）。
 *
 * DevHeaderAuthenticationFilter の `X-Dev-User` に以下のIDを指定して使用する。
 */
@Configuration
@Profile("local", "test")
class DevUserSeeder {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @ConditionalOnProperty("cf.seed.dev-users", havingValue = "true", matchIfMissing = true)
    fun devUserSeedRunner(jdbcTemplate: JdbcTemplate, clock: Clock): CommandLineRunner = CommandLineRunner {
        DEV_USERS.forEach { (userId, email, name, roles) ->
            val exists = jdbcTemplate.queryForObject(
                "select count(*) from app_user where user_id = ?",
                Int::class.java,
                userId,
            ) ?: 0
            if (exists == 0) {
                val now = java.sql.Timestamp.from(clock.instant())
                jdbcTemplate.update(
                    """
                        insert into app_user
                            (user_id, cognito_subject, email, display_name, status, version, created_at, updated_at)
                        values (?, ?, ?, ?, 'ACTIVE', 0, ?, ?)
                    """.trimIndent(),
                    userId,
                    "local|$userId",
                    email,
                    name,
                    now,
                    now,
                )
                roles.forEach { role ->
                    jdbcTemplate.update(
                        "insert into user_role (user_id, role_code, assigned_at, assigned_by) values (?, ?, ?, ?)",
                        userId,
                        role,
                        now,
                        userId,
                    )
                }
                log.info("Seeded dev user: {} ({})", name, userId)
            }
        }
    }

    companion object {
        const val DEV_OWNER_ID = "01K00000000000000000000001"
        const val DEV_REVIEWER_ID = "01K00000000000000000000002"
        const val DEV_ADMIN_ID = "01K00000000000000000000003"
        const val DEV_SUPPORTER_ID = "01K00000000000000000000004"

        /** (userId, email, displayName, roles) — すべて架空データ。 */
        val DEV_USERS = listOf(
            Seed(DEV_OWNER_ID, "owner@example.invalid", "開発用起案者", listOf("OWNER", "SUPPORTER")),
            Seed(DEV_REVIEWER_ID, "reviewer@example.invalid", "開発用審査担当", listOf("REVIEWER")),
            Seed(DEV_ADMIN_ID, "admin@example.invalid", "開発用管理者", listOf("ADMIN", "OPERATOR", "AUDITOR")),
            Seed(DEV_SUPPORTER_ID, "supporter@example.invalid", "開発用支援者", listOf("SUPPORTER")),
        )
    }

    data class Seed(val userId: String, val email: String, val displayName: String, val roles: List<String>)
}
