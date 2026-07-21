package com.example.cf.integration

import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * バッチ多重起動防止（ShedLock、基本設計 §8.3、ADR-0003）の結合テスト。
 *
 * testプロファイルではスケジューリングを無効化しているため（SchedulingConfigが@Profile("!test")）、
 * ここでは LockProvider を明示的に生成し、同名ロックが同時取得できないこと（多重起動防止）と、
 * shedlockテーブルへロック行が記録されることを検証する。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ShedLockConfigIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")
    }

    /** testでは SchedulingConfig が無効なので、テスト用にLockProviderを供給する。 */
    @TestConfiguration
    class LockProviderTestConfig {
        @Bean
        fun testLockProvider(dataSource: DataSource): LockProvider = net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider(
            net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )
    }

    @Autowired
    lateinit var lockProvider: LockProvider

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun config(name: String) = LockConfiguration(
        Instant.now(),
        name,
        Duration.ofMinutes(5), // lockAtMostFor
        Duration.ZERO, // lockAtLeastFor
    )

    @Test
    fun `同名ロックは同時取得できずshedlock行が記録される`() {
        val name = "BAT-TEST-${System.nanoTime()}"

        val first = lockProvider.lock(config(name))
        assertTrue(first.isPresent, "1つ目のロックは取得できる")

        // 保持中は同名ロックを取得できない（別インスタンスの多重起動を防止）
        val second = lockProvider.lock(config(name))
        assertFalse(second.isPresent, "保持中の同名ロックは取得できない")

        // shedlockテーブルにロック行が記録される
        val rows = jdbcTemplate.queryForObject(
            "select count(*) from shedlock where name = ?",
            Int::class.java,
            name,
        ) ?: 0
        assertEquals(1, rows, "shedlockにロック行が1件記録される")

        // 解放後は再取得できる
        first.get().unlock()
        val third = lockProvider.lock(config(name))
        assertTrue(third.isPresent, "解放後は再取得できる")
        third.get().unlock()
    }

    @Test
    fun `異なる名前のロックは互いに影響しない`() {
        val a = lockProvider.lock(config("BAT-TEST-A-${System.nanoTime()}"))
        val b = lockProvider.lock(config("BAT-TEST-B-${System.nanoTime()}"))
        assertTrue(a.isPresent && b.isPresent, "別名ロックはどちらも取得できる")
        a.get().unlock()
        b.get().unlock()
    }
}
