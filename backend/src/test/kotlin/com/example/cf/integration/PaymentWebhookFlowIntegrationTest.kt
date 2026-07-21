package com.example.cf.integration

import com.example.cf.config.DevUserSeeder
import com.example.cf.payment.adapter.out.gateway.SandboxPaymentGatewayAdapter
import com.example.cf.shared.outbox.OutboxWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 決済Webhook・Outbox配送の結合テスト（API-PY-001、BAT-006、詳細設計 §5.4 / §9.1）。
 * 決済SandboxはSandboxPaymentGatewayAdapter（教育用）を使用する。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PaymentWebhookFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")

        const val SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val WEBHOOK_SECRET = "local-sandbox-webhook-secret"
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var outboxWorker: OutboxWorker

    // 既定のSimpleClientHttpRequestFactory（HttpURLConnection）は401応答の本文を破棄するため、
    // エラー応答のcodeを検証できるようJDK HttpClientベースのFactoryを使用する。
    private val rest = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(response: ClientHttpResponse): Boolean = false
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private var projectId: String = ""
    private var supportId: String = ""
    private var paymentId: String = ""
    private var providerPaymentId: String = ""

    private fun headers(userId: String, roles: String, idempotencyKey: String? = null): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Dev-User", userId)
        set("X-Dev-Roles", roles)
        idempotencyKey?.let { set("Idempotency-Key", it) }
    }

    private fun ownerHeaders() = headers(DevUserSeeder.DEV_OWNER_ID, "OWNER,SUPPORTER")

    @Suppress("UNCHECKED_CAST")
    private fun dataOf(body: Map<*, *>?): Map<String, Any?> = body?.get("data") as Map<String, Any?>

    private fun webhookHeaders(rawBody: String, timestamp: String = Instant.now().toString()): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Sandbox-Timestamp", timestamp)
        set("X-Sandbox-Signature", SandboxPaymentGatewayAdapter.signForTesting(WEBHOOK_SECRET, timestamp, rawBody))
    }

    private fun webhookBody(eventId: String, eventType: String, extra: String = "") = """
        {"eventId":"$eventId","eventType":"$eventType","providerPaymentId":"$providerPaymentId"$extra}
    """.trimIndent()

    @Test
    @Order(1)
    fun `支援申込からPaymentRequestedがOutboxへ積まれる`() {
        val issue = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(
                """{"purpose":"PROJECT_MAIN","fileName":"main.jpg","contentType":"image/jpeg","size":204800,"sha256":"$SHA256"}""",
                ownerHeaders(),
            ),
            Map::class.java,
        )
        val fileId = dataOf(issue.body)["fileId"] as String
        rest.postForEntity(
            url("/api/v1/files/$fileId/complete"),
            HttpEntity("""{"sha256":"$SHA256"}""", ownerHeaders()),
            Map::class.java,
        )

        val create = """
            {
              "title": "Webhookテスト用プロジェクト",
              "summary": "結合テスト用の合成データです。",
              "body": "本文テキスト。",
              "targetAmount": 500000,
              "fundingType": "ALL_OR_NOTHING",
              "startAt": "2027-01-01T00:00:00Z",
              "endAt": "2027-02-01T00:00:00Z",
              "mainFileId": "$fileId",
              "rewardPlans": [
                {"name": "お礼メール", "description": "感謝のメールをお送りします。", "unitAmount": 3000, "displayOrder": 1}
              ]
            }
        """.trimIndent()
        val project = rest.postForEntity(
            url("/api/v1/owner/projects"),
            HttpEntity(create, ownerHeaders()),
            Map::class.java,
        )
        projectId = dataOf(project.body)["projectId"] as String

        val now = Instant.now()
        jdbcTemplate.update(
            "update project set status = 'PUBLISHED', start_at = ?, end_at = ? where project_id = ?",
            Timestamp.from(now.minus(1, ChronoUnit.DAYS)),
            Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
            projectId,
        )

        val support = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(
                """{"quantity":1,"additionalAmount":5000,"contactEmail":"supporter@example.invalid","termsAccepted":true}""",
                headers(DevUserSeeder.DEV_SUPPORTER_ID, "SUPPORTER", "key-webhook-0001"),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, support.statusCode, "body=${support.body}")
        supportId = dataOf(support.body)["supportId"] as String

        paymentId = jdbcTemplate.queryForObject(
            "select payment_id from support where support_id = ?",
            String::class.java,
            supportId,
        )!!

        val pending = jdbcTemplate.queryForObject(
            "select count(*) from outbox_event where event_type = 'PaymentRequested' and publish_status = 'PENDING'",
            Int::class.java,
        ) ?: 0
        assertTrue(pending >= 1, "PaymentRequestedがPENDINGで積まれること: $pending")
    }

    @Test
    @Order(2)
    fun `Outbox Workerが配送しPaymentがPROCESSINGになる`() {
        val processed = outboxWorker.publishBatch()
        assertTrue(processed >= 1, "配送対象が処理されること: $processed")

        val status = jdbcTemplate.queryForObject(
            "select status from payment where payment_id = ?",
            String::class.java,
            paymentId,
        )
        assertEquals("PROCESSING", status, "Sandbox受付でPROCESSINGへ遷移すること")

        providerPaymentId = jdbcTemplate.queryForObject(
            "select provider_payment_id from payment where payment_id = ?",
            String::class.java,
            paymentId,
        )!!
        assertNotNull(providerPaymentId)

        val published = jdbcTemplate.queryForObject(
            "select count(*) from outbox_event where publish_status = 'PUBLISHED'",
            Int::class.java,
        ) ?: 0
        assertTrue(published >= 1, "配送済みイベントがPUBLISHEDになること: $published")
    }

    @Test
    @Order(3)
    fun `署名不正のWebhookは401`() {
        val body = webhookBody("evt-invalid-0001", "payment.succeeded")
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Sandbox-Timestamp", Instant.now().toString())
            set("X-Sandbox-Signature", "invalid-signature")
        }
        val response = rest.postForEntity(url("/api/v1/payments/webhooks"), HttpEntity(body, headers), Map::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals("PAYMENT_SIGNATURE_INVALID", response.body?.get("code"))
    }

    @Test
    @Order(4)
    fun `時刻許容差を超えたWebhookは401`() {
        val stale = Instant.now().minus(10, ChronoUnit.MINUTES).toString()
        val body = webhookBody("evt-stale-0001", "payment.succeeded")
        val response = rest.postForEntity(
            url("/api/v1/payments/webhooks"),
            HttpEntity(body, webhookHeaders(body, stale)),
            Map::class.java,
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    @Order(5)
    fun `決済成功WebhookでPaymentとSupportが確定する`() {
        val body = webhookBody("evt-success-0001", "payment.succeeded")
        val response = rest.postForEntity(
            url("/api/v1/payments/webhooks"),
            HttpEntity(body, webhookHeaders(body)),
            Map::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)

        assertEquals(
            "SUCCEEDED",
            jdbcTemplate.queryForObject("select status from payment where payment_id = ?", String::class.java, paymentId),
        )
        assertEquals(
            "PAID",
            jdbcTemplate.queryForObject("select status from support where support_id = ?", String::class.java, supportId),
        )
        assertEquals(
            "PROCESSED",
            jdbcTemplate.queryForObject(
                "select process_status from payment_webhook_event where webhook_event_id = ?",
                String::class.java,
                "evt-success-0001",
            ),
        )
    }

    @Test
    @Order(6)
    fun `同一eventIdの再送は重複排除され204を返す`() {
        val body = webhookBody("evt-success-0001", "payment.succeeded")
        val response = rest.postForEntity(
            url("/api/v1/payments/webhooks"),
            HttpEntity(body, webhookHeaders(body)),
            Map::class.java,
        )
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)

        val count = jdbcTemplate.queryForObject(
            "select count(*) from payment_webhook_event where webhook_event_id = ?",
            Int::class.java,
            "evt-success-0001",
        ) ?: 0
        assertEquals(1, count, "受信履歴が二重登録されないこと")

        // 状態も二重遷移しない
        assertEquals(
            "SUCCEEDED",
            jdbcTemplate.queryForObject("select status from payment where payment_id = ?", String::class.java, paymentId),
        )
    }

    @Test
    @Order(7)
    fun `同一eventIdでpayloadが異なる場合はERROR記録される`() {
        val body = webhookBody("evt-success-0001", "payment.succeeded", extra = ""","failureCode":"TAMPERED"""")
        rest.postForEntity(url("/api/v1/payments/webhooks"), HttpEntity(body, webhookHeaders(body)), Map::class.java)

        assertEquals(
            "ERROR",
            jdbcTemplate.queryForObject(
                "select process_status from payment_webhook_event where webhook_event_id = ?",
                String::class.java,
                "evt-success-0001",
            ),
        )
    }

    @Test
    @Order(8)
    fun `確定後の重複した成功Webhookは状態不整合としてERROR記録される`() {
        val body = webhookBody("evt-success-0002", "payment.succeeded")
        val response = rest.postForEntity(
            url("/api/v1/payments/webhooks"),
            HttpEntity(body, webhookHeaders(body)),
            Map::class.java,
        )
        // 再送しても解決しないため204で受理し、履歴にERRORを残す（§5.4-9）
        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        assertEquals(
            "ERROR",
            jdbcTemplate.queryForObject(
                "select process_status from payment_webhook_event where webhook_event_id = ?",
                String::class.java,
                "evt-success-0002",
            ),
        )
        assertEquals(
            "PAYMENT_INVALID_STATE",
            jdbcTemplate.queryForObject(
                "select last_error_code from payment_webhook_event where webhook_event_id = ?",
                String::class.java,
                "evt-success-0002",
            ),
        )
    }
}
