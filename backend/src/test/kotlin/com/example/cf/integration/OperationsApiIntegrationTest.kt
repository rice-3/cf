package com.example.cf.integration

import com.example.cf.config.DevUserSeeder
import com.example.cf.payment.adapter.`in`.batch.PaymentScheduledBatches
import com.example.cf.payment.adapter.out.gateway.SandboxPaymentGatewayAdapter
import com.example.cf.project.application.usecase.PublishApprovedProjectsUseCase
import com.example.cf.shared.batch.batchAuditContext
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
 * 運用操作APIの結合テスト（API-RF-001 / API-RF-002 / API-PY-002、基本設計 §6.5）。
 * OPERATORによる手動返金・再実行・決済照合を検証する。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OperationsApiIntegrationTest {

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

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var publishApprovedProjects: PublishApprovedProjectsUseCase

    @Autowired lateinit var outboxWorker: OutboxWorker

    @Autowired lateinit var paymentBatches: PaymentScheduledBatches

    private val rest = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(response: ClientHttpResponse): Boolean = false
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private var supportId: String = ""
    private var paymentId: String = ""
    private var refundId: String = ""

    private fun headers(userId: String, roles: String, idempotencyKey: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Dev-User", userId)
        set("X-Dev-Roles", roles)
        idempotencyKey?.let { set("Idempotency-Key", it) }
    }

    private fun ownerHeaders() = headers(DevUserSeeder.DEV_OWNER_ID, "OWNER,SUPPORTER")

    private fun operatorHeaders(idempotencyKey: String? = null) = headers(DevUserSeeder.DEV_ADMIN_ID, "OPERATOR,ADMIN", idempotencyKey)

    @Suppress("UNCHECKED_CAST")
    private fun dataOf(body: Map<*, *>?) = body?.get("data") as Map<String, Any?>

    @Test
    @Order(1)
    fun `決済確定済みの支援を用意する`() {
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
              "title": "運用APIテスト用プロジェクト",
              "summary": "結合テスト用の合成データです。",
              "body": "本文テキスト。",
              "targetAmount": 500000,
              "fundingType": "ALL_IN",
              "startAt": "2027-01-01T00:00:00Z",
              "endAt": "2027-02-01T00:00:00Z",
              "mainFileId": "$fileId",
              "rewardPlans": [
                {"name": "お礼メール", "description": "感謝のメールをお送りします。", "unitAmount": 3000, "displayOrder": 1}
              ]
            }
        """.trimIndent()
        val projectId = dataOf(
            rest.postForEntity(url("/api/v1/owner/projects"), HttpEntity(create, ownerHeaders()), Map::class.java).body,
        )["projectId"] as String

        val now = Instant.now()
        jdbcTemplate.update(
            "update project set status = 'APPROVED', start_at = ?, end_at = ? where project_id = ?",
            Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
            Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
            projectId,
        )
        publishApprovedProjects.execute(100, batchAuditContext())

        val support = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(
                """{"quantity":1,"additionalAmount":5000,"contactEmail":"supporter@example.invalid","termsAccepted":true}""",
                headers(DevUserSeeder.DEV_SUPPORTER_ID, "SUPPORTER", "key-ops-0001"),
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

        outboxWorker.publishBatch()
        val providerPaymentId = jdbcTemplate.queryForObject(
            "select provider_payment_id from payment where payment_id = ?",
            String::class.java,
            paymentId,
        )!!

        val body =
            """{"eventId":"evt-ops-0001","eventType":"payment.succeeded","providerPaymentId":"$providerPaymentId"}"""
        val timestamp = Instant.now().toString()
        val webhookHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Sandbox-Timestamp", timestamp)
            set("X-Sandbox-Signature", SandboxPaymentGatewayAdapter.signForTesting(WEBHOOK_SECRET, timestamp, body))
        }
        rest.postForEntity(url("/api/v1/payments/webhooks"), HttpEntity(body, webhookHeaders), Map::class.java)
        assertEquals(
            "PAID",
            jdbcTemplate.queryForObject("select status from support where support_id = ?", String::class.java, supportId),
        )
    }

    @Test
    @Order(2)
    fun `支援者は運用APIを実行できない`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/supports/$supportId/refunds"),
            HttpEntity(
                """{"reasonCode":"OPERATIONAL","comment":"重複支援のため"}""",
                headers(DevUserSeeder.DEV_SUPPORTER_ID, "SUPPORTER", "key-refund-0001"),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    @Order(3)
    fun `Idempotency-Keyなしの返金要求は400`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/supports/$supportId/refunds"),
            HttpEntity("""{"reasonCode":"OPERATIONAL","comment":"重複支援のため"}""", operatorHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", response.body?.get("code"))
    }

    @Test
    @Order(4)
    fun `運用理由でコメントなしは409になる`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/supports/$supportId/refunds"),
            HttpEntity("""{"reasonCode":"OPERATIONAL"}""", operatorHeaders("key-refund-nocomment")),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("REFUND_COMMENT_REQUIRED", response.body?.get("code"))
    }

    @Test
    @Order(5)
    fun `OPERATORは返金を要求できる（API-RF-001）`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/supports/$supportId/refunds"),
            HttpEntity(
                """{"reasonCode":"OPERATIONAL","comment":"重複支援のため"}""",
                operatorHeaders("key-refund-0001"),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        refundId = data["refundId"] as String
        assertEquals("REQUESTED", data["status"])
        assertNotNull(refundId)

        assertEquals(
            "REFUND_REQUESTED",
            jdbcTemplate.queryForObject("select status from support where support_id = ?", String::class.java, supportId),
        )
        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where action = 'REFUND_REQUEST' and resource_id = ?",
            Int::class.java,
            refundId,
        ) ?: 0
        assertTrue(auditCount >= 1, "返金要求が監査ログへ記録されること")
    }

    @Test
    @Order(6)
    fun `同一キーの再送は初回結果を返す（冪等）`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/supports/$supportId/refunds"),
            HttpEntity(
                """{"reasonCode":"OPERATIONAL","comment":"重複支援のため"}""",
                operatorHeaders("key-refund-0001"),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals(refundId, dataOf(response.body)["refundId"])

        val count = jdbcTemplate.queryForObject(
            "select count(*) from refund where support_id = ?",
            Int::class.java,
            supportId,
        ) ?: 0
        assertEquals(1, count, "冪等再送で返金が二重作成されないこと")
    }

    @Test
    @Order(7)
    fun `有効な返金がある支援への再要求は409`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/supports/$supportId/refunds"),
            HttpEntity(
                """{"reasonCode":"USER_CANCEL"}""",
                operatorHeaders("key-refund-0002"),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("REFUND_ALREADY_EXISTS", response.body?.get("code"))
    }

    @Test
    @Order(8)
    fun `返金が実行され支援が返金済みになる`() {
        paymentBatches.runRefundBatch()
        assertEquals(
            "SUCCEEDED",
            jdbcTemplate.queryForObject("select status from refund where refund_id = ?", String::class.java, refundId),
        )
        assertEquals(
            "REFUNDED",
            jdbcTemplate.queryForObject("select status from support where support_id = ?", String::class.java, supportId),
        )
    }

    @Test
    @Order(9)
    fun `成功済みの返金は再実行できない（API-RF-002）`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/refunds/$refundId/retry"),
            HttpEntity("", operatorHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("REFUND_INVALID_STATE", response.body?.get("code"))
    }

    @Test
    @Order(10)
    fun `失敗した返金はOPERATORが再実行できる（API-RF-002）`() {
        // BAT-004で上限超過したFAILED状態を再現する
        jdbcTemplate.update(
            "update refund set status = 'FAILED', retry_count = 8 where refund_id = ?",
            refundId,
        )
        val response = rest.postForEntity(
            url("/api/v1/operations/refunds/$refundId/retry"),
            HttpEntity("", operatorHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        assertEquals("REQUESTED", dataOf(response.body)["status"])

        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where action = 'REFUND_RETRY' and resource_id = ?",
            Int::class.java,
            refundId,
        ) ?: 0
        assertTrue(auditCount >= 1, "再実行操作が監査ログへ記録されること（基本設計 §8.2）")
    }

    @Test
    @Order(11)
    fun `存在しない返金の再実行は404`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/refunds/01K00000000000000000009999/retry"),
            HttpEntity("", operatorHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("REFUND_NOT_FOUND", response.body?.get("code"))
    }

    @Test
    @Order(12)
    fun `OPERATORは決済を照合できる（API-PY-002）`() {
        // Webhookが届かずUNKNOWNのまま滞留した決済を再現する
        jdbcTemplate.update("update payment set status = 'UNKNOWN' where payment_id = ?", paymentId)

        val response = rest.postForEntity(
            url("/api/v1/operations/payments/$paymentId/reconcile"),
            HttpEntity("", operatorHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        assertEquals(paymentId, data["paymentId"])
        // Sandboxは照会に対しUNKNOWNを返すため状態は変わらない（次回以降に再照会）
        assertEquals("UNKNOWN", data["status"])
    }

    @Test
    @Order(13)
    fun `存在しない決済の照合は404`() {
        val response = rest.postForEntity(
            url("/api/v1/operations/payments/01K00000000000000000009999/reconcile"),
            HttpEntity("", operatorHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("PAYMENT_NOT_FOUND", response.body?.get("code"))
    }
}
