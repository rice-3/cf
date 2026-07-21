package com.example.cf.integration

import com.example.cf.config.DevUserSeeder
import com.example.cf.file.application.FileCleanupUseCase
import com.example.cf.notification.adapter.`in`.batch.NotificationSendBatch
import com.example.cf.payment.adapter.`in`.batch.PaymentScheduledBatches
import com.example.cf.payment.adapter.out.gateway.SandboxPaymentGatewayAdapter
import com.example.cf.project.application.usecase.CloseFundingUseCase
import com.example.cf.project.application.usecase.PublishApprovedProjectsUseCase
import com.example.cf.shared.batch.batchAuditContext
import com.example.cf.shared.outbox.OutboxWorker
import org.junit.jupiter.api.Assertions.assertEquals
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
 * 工程8バッチの結合テスト（基本設計 §8.1）。
 *
 * 募集不成立から返金・通知までを一気通貫で検証する:
 * BAT-001 公開開始 → 支援・決済 → BAT-002 募集終了(不成立) → BAT-006 Outbox配送
 * → BAT-003 返金対象作成 → BAT-004 返金実行 → BAT-005 通知送信。
 * スケジュール起動はtestプロファイルで無効化しており、各処理を明示的に呼び出す。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BatchFlowIntegrationTest {

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

    @Autowired lateinit var closeFunding: CloseFundingUseCase

    @Autowired lateinit var outboxWorker: OutboxWorker

    @Autowired lateinit var paymentBatches: PaymentScheduledBatches

    @Autowired lateinit var notificationBatch: NotificationSendBatch

    @Autowired lateinit var fileCleanup: FileCleanupUseCase

    @Autowired lateinit var idempotencyCleanup: com.example.cf.shared.idempotency.IdempotencyCleanupUseCase

    private val rest = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(response: ClientHttpResponse): Boolean = false
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private var projectId: String = ""
    private var supportId: String = ""
    private var paymentId: String = ""

    private fun headers(userId: String, roles: String, idempotencyKey: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Dev-User", userId)
        set("X-Dev-Roles", roles)
        idempotencyKey?.let { set("Idempotency-Key", it) }
    }

    private fun ownerHeaders() = headers(DevUserSeeder.DEV_OWNER_ID, "OWNER,SUPPORTER")

    @Suppress("UNCHECKED_CAST")
    private fun dataOf(body: Map<*, *>?) = body?.get("data") as Map<String, Any?>

    @Test
    @Order(1)
    fun `BAT-001が承認済みかつ開始時刻到達のプロジェクトを公開する`() {
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
              "title": "バッチテスト用プロジェクト",
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
        projectId = dataOf(
            rest.postForEntity(url("/api/v1/owner/projects"), HttpEntity(create, ownerHeaders()), Map::class.java).body,
        )["projectId"] as String

        // 承認済み・開始時刻到達・終了は未到達の状態へ整える
        val now = Instant.now()
        jdbcTemplate.update(
            "update project set status = 'APPROVED', start_at = ?, end_at = ? where project_id = ?",
            Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
            Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
            projectId,
        )

        val published = publishApprovedProjects.execute(100, batchAuditContext())
        assertTrue(published >= 1, "BAT-001が対象を公開すること: $published")
        assertEquals(
            "PUBLISHED",
            jdbcTemplate.queryForObject("select status from project where project_id = ?", String::class.java, projectId),
        )
    }

    @Test
    @Order(2)
    fun `支援を申し込み決済を成功させる`() {
        val support = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(
                """{"quantity":1,"additionalAmount":5000,"contactEmail":"supporter@example.invalid","termsAccepted":true}""",
                headers(DevUserSeeder.DEV_SUPPORTER_ID, "SUPPORTER", "key-batch-0001"),
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

        // BAT-006 Outbox配送 → Sandboxが受け付けPROCESSINGへ
        outboxWorker.publishBatch()
        val providerPaymentId = jdbcTemplate.queryForObject(
            "select provider_payment_id from payment where payment_id = ?",
            String::class.java,
            paymentId,
        )!!

        // 決済成功Webhookで確定させる
        val body = """{"eventId":"evt-batch-0001","eventType":"payment.succeeded","providerPaymentId":"$providerPaymentId"}"""
        val timestamp = Instant.now().toString()
        val webhookHeaders = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Sandbox-Timestamp", timestamp)
            set("X-Sandbox-Signature", SandboxPaymentGatewayAdapter.signForTesting(WEBHOOK_SECRET, timestamp, body))
        }
        assertEquals(
            HttpStatus.NO_CONTENT,
            rest.postForEntity(url("/api/v1/payments/webhooks"), HttpEntity(body, webhookHeaders), Map::class.java)
                .statusCode,
        )
        assertEquals(
            "PAID",
            jdbcTemplate.queryForObject("select status from support where support_id = ?", String::class.java, supportId),
        )
    }

    @Test
    @Order(3)
    fun `BAT-002が終了時刻到達の案件を不成立と判定する`() {
        // 目標50万円に対し支援5,000円のみ。All-or-Nothingのため不成立になる
        jdbcTemplate.update(
            "update project set end_at = ? where project_id = ?",
            Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)),
            projectId,
        )

        val closed = closeFunding.execute(100, batchAuditContext())
        assertTrue(closed >= 1, "BAT-002が対象を判定すること: $closed")
        assertEquals(
            "FAILED",
            jdbcTemplate.queryForObject("select status from project where project_id = ?", String::class.java, projectId),
        )

        val failedEvents = jdbcTemplate.queryForObject(
            "select count(*) from outbox_event where event_type = 'ProjectFailed' and aggregate_id = ?",
            Int::class.java,
            projectId,
        ) ?: 0
        assertEquals(1, failedEvents, "ProjectFailedが1件積まれること")
    }

    @Test
    @Order(4)
    fun `BAT-003がProjectFailedを購読して返金要求を作成する`() {
        outboxWorker.publishBatch()

        val refundCount = jdbcTemplate.queryForObject(
            "select count(*) from refund where support_id = ?",
            Int::class.java,
            supportId,
        ) ?: 0
        assertEquals(1, refundCount, "返金要求が作成されること")
        assertEquals(
            "REQUESTED",
            jdbcTemplate.queryForObject("select status from refund where support_id = ?", String::class.java, supportId),
        )
        assertEquals(
            "REFUND_REQUESTED",
            jdbcTemplate.queryForObject("select status from support where support_id = ?", String::class.java, supportId),
        )
    }

    @Test
    @Order(5)
    fun `ProjectFailedを再配送しても返金は二重作成されない`() {
        jdbcTemplate.update(
            "update outbox_event set publish_status = 'PENDING', published_at = null where event_type = 'ProjectFailed'",
        )
        outboxWorker.publishBatch()

        val refundCount = jdbcTemplate.queryForObject(
            "select count(*) from refund where support_id = ?",
            Int::class.java,
            supportId,
        ) ?: 0
        assertEquals(1, refundCount, "重複受信でも返金は1件のまま")
    }

    @Test
    @Order(6)
    fun `BAT-004が返金を実行しSupportとPaymentが返金済みになる`() {
        val processed = paymentBatches.runRefundBatch()
        assertTrue(processed >= 1, "BAT-004が対象を処理すること: $processed")

        assertEquals(
            "SUCCEEDED",
            jdbcTemplate.queryForObject("select status from refund where support_id = ?", String::class.java, supportId),
        )
        assertEquals(
            "REFUNDED",
            jdbcTemplate.queryForObject("select status from support where support_id = ?", String::class.java, supportId),
        )
        assertEquals(
            "REFUNDED",
            jdbcTemplate.queryForObject("select status from payment where payment_id = ?", String::class.java, paymentId),
        )
    }

    @Test
    @Order(7)
    fun `BAT-005がRefundCompletedの通知を送信する`() {
        // RefundCompletedを配送して通知レコードを作らせる
        outboxWorker.publishBatch()

        val pending = jdbcTemplate.queryForObject(
            "select count(*) from notification where template_id = 'REFUND_COMPLETED'",
            Int::class.java,
        ) ?: 0
        assertEquals(1, pending, "返金完了通知が登録されること")

        val sent = notificationBatch.runBatch()
        assertTrue(sent >= 1, "BAT-005が送信すること: $sent")
        assertEquals(
            "SENT",
            jdbcTemplate.queryForObject(
                "select status from notification where template_id = 'REFUND_COMPLETED'",
                String::class.java,
            ),
        )

        val deliveries = jdbcTemplate.queryForObject(
            "select count(*) from notification_delivery where result = 'SUCCESS'",
            Int::class.java,
        ) ?: 0
        assertTrue(deliveries >= 1, "送信試行が記録されること: $deliveries")
    }

    @Test
    @Order(8)
    fun `BAT-008が失効した未完了アップロードを削除する`() {
        val issue = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(
                """{"purpose":"PROJECT_ATTACHMENT","fileName":"stale.pdf","contentType":"application/pdf","size":1024,"sha256":"$SHA256"}""",
                ownerHeaders(),
            ),
            Map::class.java,
        )
        val staleFileId = dataOf(issue.body)["fileId"] as String
        jdbcTemplate.update(
            "update file_object set expires_at = ? where file_id = ?",
            Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
            staleFileId,
        )

        val deleted = fileCleanup.execute(1000, batchAuditContext())
        assertTrue(deleted >= 1, "BAT-008が対象を削除すること: $deleted")
        assertEquals(
            "DELETED",
            jdbcTemplate.queryForObject(
                "select status from file_object where file_id = ?",
                String::class.java,
                staleFileId,
            ),
        )
    }

    @Test
    @Order(9)
    fun `不成立時に起案者へProjectFailed通知が登録される（ADR-0002）`() {
        // Order3でProjectFailedが発行され、Order4以降のpublishBatchでNotificationEventHandlerへ配送済み
        val count = jdbcTemplate.queryForObject(
            "select count(*) from notification where template_id = 'PROJECT_FAILED' and recipient_user_id = ?",
            Int::class.java,
            DevUserSeeder.DEV_OWNER_ID,
        ) ?: 0
        assertEquals(1, count, "起案者向けPROJECT_FAILED通知が1件登録されること")
    }

    @Test
    @Order(10)
    fun `BAT-010が失効した冪等記録のみを削除する`() {
        val now = Instant.now()
        // 失効済み1件・有効1件を投入
        jdbcTemplate.update(
            """
            insert into idempotency_record
                (scope, actor_id, idempotency_key, request_hash, status, expires_at, created_at)
            values ('TEST', 'actor', 'expired-key', 'h', 'COMPLETED', ?, ?)
            """.trimIndent(),
            Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
            Timestamp.from(now.minus(25, ChronoUnit.HOURS)),
        )
        jdbcTemplate.update(
            """
            insert into idempotency_record
                (scope, actor_id, idempotency_key, request_hash, status, expires_at, created_at)
            values ('TEST', 'actor', 'valid-key', 'h', 'COMPLETED', ?, ?)
            """.trimIndent(),
            Timestamp.from(now.plus(1, ChronoUnit.HOURS)),
            Timestamp.from(now),
        )

        val deleted = idempotencyCleanup.execute(10_000)
        assertTrue(deleted >= 1, "BAT-010が失効記録を削除すること: $deleted")
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "select count(*) from idempotency_record where scope = 'TEST' and idempotency_key = 'expired-key'",
                Int::class.java,
            ),
            "失効記録は削除される",
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "select count(*) from idempotency_record where scope = 'TEST' and idempotency_key = 'valid-key'",
                Int::class.java,
            ),
            "有効な記録は残る",
        )
    }
}
