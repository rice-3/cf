package com.example.cf.integration

import com.example.cf.shared.observability.BatchMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * メトリクス公開の検証（詳細設計 §12.5–12.6/§9.3）。
 *
 * `/actuator/prometheus` が認証なしで公開され、ビジネスメトリクス（滞留・失敗・バッチ稼働）と
 * APIレイテンシメトリクスが含まれることを確認する。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var batchMetrics: BatchMetrics

    private val client = RestTemplate()

    private fun scrape(): String = client.getForObject("http://localhost:$port/actuator/prometheus", String::class.java)!!

    @Test
    fun `prometheusエンドポイントが認証なしで公開される`() {
        val response = client.getForEntity("http://localhost:$port/actuator/prometheus", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `ビジネス滞留メトリクスが公開される`() {
        val body = scrape()
        assertTrue(body.contains("cf_outbox_pending_count"), "Outbox滞留ゲージが公開されること")
        assertTrue(body.contains("cf_outbox_oldest_age_seconds"), "Outbox最古経過ゲージが公開されること")
        assertTrue(body.contains("cf_notification_pending_count"), "通知滞留ゲージが公開されること")
        assertTrue(body.contains("cf_notification_failed_count"), "通知失敗ゲージが公開されること")
        assertTrue(body.contains("cf_refund_retry_wait_count"), "返金再試行待ちゲージが公開されること")
        assertTrue(body.contains("cf_refund_failed_count"), "返金失敗ゲージが公開されること")
        // 全メーターに application 共通タグが付与される
        assertTrue(body.contains("""application="cf-api""""), "application共通タグが付与されること")
    }

    @Test
    fun `バッチ成功記録後に最終成功メトリクスが公開される`() {
        // testプロファイルはスケジュール起動を止めるため、バッチ成功の記録を直接呼び出す。
        batchMetrics.recordSuccess("BAT-006-outbox")
        val body = scrape()
        assertTrue(
            body.contains("cf_batch_last_success_age_seconds") && body.contains("""batch="BAT-006-outbox""""),
            "バッチ成功後に最終成功経過ゲージが登録されること",
        )
        assertTrue(
            body.contains("cf_batch_runs_total") && body.contains("""outcome="success""""),
            "バッチ実行回数カウンタが記録されること",
        )
    }
}
