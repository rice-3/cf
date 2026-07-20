package com.example.cf.integration

import com.example.cf.config.DevUserSeeder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import org.springframework.http.HttpMethod
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
 * 支援申込フローの結合テスト（API-FD-001〜004、詳細設計 §5.3）。
 * 冪等性・数量予約・支援可否・所有権の検証を含む。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SupportFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")

        const val SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    // 401応答の本文を検証できるようJDK HttpClientベースのFactoryを使用する
    // （既定のHttpURLConnectionは401の本文を破棄する）
    private val rest = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(response: ClientHttpResponse): Boolean = false
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private var projectId: String = ""
    private var limitedRewardId: String = ""
    private var supportId: String = ""

    private fun headers(userId: String, roles: String, idempotencyKey: String? = null): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Dev-User", userId)
            set("X-Dev-Roles", roles)
            idempotencyKey?.let { set("Idempotency-Key", it) }
        }

    private fun ownerHeaders() = headers(DevUserSeeder.DEV_OWNER_ID, "OWNER,SUPPORTER")

    private fun supporterHeaders(idempotencyKey: String? = null) =
        headers(DevUserSeeder.DEV_SUPPORTER_ID, "SUPPORTER", idempotencyKey)

    @Suppress("UNCHECKED_CAST")
    private fun dataOf(body: Map<*, *>?): Map<String, Any?> =
        body?.get("data") as Map<String, Any?>

    private fun supportBody(
        rewardPlanId: String? = null,
        quantity: Int = 1,
        additionalAmount: Long = 0,
        termsAccepted: Boolean = true,
    ): String {
        val reward = rewardPlanId?.let { """"rewardPlanId": "$it",""" } ?: ""
        return """
            {
              $reward
              "quantity": $quantity,
              "additionalAmount": $additionalAmount,
              "contactEmail": "supporter@example.invalid",
              "termsAccepted": $termsAccepted
            }
        """.trimIndent()
    }

    @Test
    @Order(1)
    fun `支援対象の公開中プロジェクトを準備する`() {
        // メイン画像（API-FL-001/002）
        val issue = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(
                """{"purpose":"PROJECT_MAIN","fileName":"main.jpg","contentType":"image/jpeg","size":204800,"sha256":"$SHA256"}""",
                ownerHeaders(),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.CREATED, issue.statusCode, "body=${issue.body}")
        val fileId = dataOf(issue.body)["fileId"] as String
        rest.postForEntity(
            url("/api/v1/files/$fileId/complete"),
            HttpEntity("""{"sha256":"$SHA256"}""", ownerHeaders()),
            Map::class.java,
        )

        val create = """
            {
              "title": "支援テスト用プロジェクト",
              "summary": "結合テスト用の合成データです。",
              "body": "本文テキスト。",
              "targetAmount": 500000,
              "fundingType": "ALL_OR_NOTHING",
              "startAt": "2027-01-01T00:00:00Z",
              "endAt": "2027-02-01T00:00:00Z",
              "mainFileId": "$fileId",
              "rewardPlans": [
                {"name": "お礼メール", "description": "感謝のメールをお送りします。", "unitAmount": 3000, "displayOrder": 1},
                {"name": "限定グッズ", "description": "数量限定のグッズです。", "unitAmount": 5000, "quantityLimit": 1, "displayOrder": 2}
              ]
            }
        """.trimIndent()
        val response = rest.postForEntity(
            url("/api/v1/owner/projects"),
            HttpEntity(create, ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CREATED, response.statusCode, "body=${response.body}")
        projectId = dataOf(response.body)["projectId"] as String

        // BAT-001 公開開始バッチは工程8で実装するため、テストでは直接PUBLISHEDかつ期間内へ調整する
        val now = Instant.now()
        jdbcTemplate.update(
            "update project set status = 'PUBLISHED', start_at = ?, end_at = ? where project_id = ?",
            Timestamp.from(now.minus(1, ChronoUnit.DAYS)),
            Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
            projectId,
        )
        limitedRewardId = jdbcTemplate.queryForObject(
            "select reward_plan_id from reward_plan where project_id = ? and quantity_limit = 1",
            String::class.java,
            projectId,
        )!!
    }

    @Test
    @Order(2)
    fun `Idempotency-Keyなしの支援申込は400`() {
        val response = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(supportBody(additionalAmount = 3000), supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", response.body?.get("code"))
    }

    @Test
    @Order(3)
    fun `規約未同意の支援申込は400`() {
        val response = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(
                supportBody(additionalAmount = 3000, termsAccepted = false),
                supporterHeaders("key-terms-0001"),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    @Order(4)
    fun `支援者は支援を申し込める`() {
        val response = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(supportBody(additionalAmount = 3000), supporterHeaders("key-support-0001")),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        supportId = data["supportId"] as String
        assertEquals("PENDING", data["paymentStatus"])
        assertNotNull(data["statusUrl"])
    }

    @Test
    @Order(5)
    fun `同一キーの再送は初回結果を返す（冪等）`() {
        val response = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(supportBody(additionalAmount = 3000), supporterHeaders("key-support-0001")),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals(supportId, dataOf(response.body)["supportId"])

        val count = jdbcTemplate.queryForObject(
            "select count(*) from support where supporter_user_id = ? and project_id = ?",
            Int::class.java,
            DevUserSeeder.DEV_SUPPORTER_ID, projectId,
        ) ?: 0
        assertEquals(1, count, "冪等再送でSupportが二重作成されないこと")
    }

    @Test
    @Order(6)
    fun `同一キーで内容が異なる要求は409`() {
        val response = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(supportBody(additionalAmount = 9999), supporterHeaders("key-support-0001")),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", response.body?.get("code"))
    }

    @Test
    @Order(7)
    fun `SupportとPaymentとOutboxが記録されている`() {
        val support = jdbcTemplate.queryForList(
            "select status, support_amount, payment_id from support where support_id = ?",
            supportId,
        ).first()
        assertEquals("PENDING", support["status"])
        assertEquals(3000L, support["support_amount"])
        assertNotNull(support["payment_id"], "PaymentがSupportへ紐付いていること")

        val payment = jdbcTemplate.queryForList(
            "select status, provider, amount from payment where payment_id = ?",
            support["payment_id"],
        ).first()
        assertEquals("CREATED", payment["status"])
        assertEquals("SANDBOX", payment["provider"])

        val outboxCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_event where event_type in ('SupportRequested', 'PaymentRequested')",
            Int::class.java,
        ) ?: 0
        assertTrue(outboxCount >= 2, "SupportRequested/PaymentRequestedが積まれること: $outboxCount")

        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where resource_type = 'Support' and resource_id = ?",
            Int::class.java,
            supportId,
        ) ?: 0
        assertTrue(auditCount >= 1, "監査ログが記録されること: $auditCount")
    }

    @Test
    @Order(8)
    fun `数量限定リターンは在庫を1件だけ予約できる`() {
        val first = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(supportBody(rewardPlanId = limitedRewardId), supporterHeaders("key-limited-0001")),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, first.statusCode, "body=${first.body}")

        val reserved = jdbcTemplate.queryForObject(
            "select reserved_quantity from reward_plan where reward_plan_id = ?",
            Int::class.java,
            limitedRewardId,
        )
        assertEquals(1, reserved)

        // 2件目は在庫切れ
        val second = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(supportBody(rewardPlanId = limitedRewardId), supporterHeaders("key-limited-0002")),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, second.statusCode)
        assertEquals("REWARD_SOLD_OUT", second.body?.get("code"))
    }

    @Test
    @Order(9)
    fun `募集期間外のプロジェクトは支援できない`() {
        val now = Instant.now()
        jdbcTemplate.update(
            "update project set end_at = ? where project_id = ?",
            Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
            projectId,
        )
        val response = rest.postForEntity(
            url("/api/v1/projects/$projectId/supports"),
            HttpEntity(supportBody(additionalAmount = 3000), supporterHeaders("key-expired-0001")),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("PROJECT_NOT_SUPPORTABLE", response.body?.get("code"))

        jdbcTemplate.update(
            "update project set end_at = ? where project_id = ?",
            Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
            projectId,
        )
    }

    @Test
    @Order(10)
    fun `自分の支援一覧と詳細を取得できる`() {
        val list = rest.exchange(
            url("/api/v1/me/supports"),
            HttpMethod.GET,
            HttpEntity<Void>(supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, list.statusCode, "body=${list.body}")
        val items = dataOf(list.body)["items"] as List<*>
        assertTrue(items.isNotEmpty(), "支援一覧が返ること")

        val detail = rest.exchange(
            url("/api/v1/me/supports/$supportId"),
            HttpMethod.GET,
            HttpEntity<Void>(supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, detail.statusCode)
        val data = dataOf(detail.body)
        assertEquals("PENDING", data["status"])
        assertEquals("CREATED", data["paymentStatus"])
        assertNotEquals("", data["projectTitle"])
    }

    @Test
    @Order(11)
    fun `他者の支援詳細は404で秘匿される`() {
        val response = rest.exchange(
            url("/api/v1/me/supports/$supportId"),
            HttpMethod.GET,
            HttpEntity<Void>(headers(DevUserSeeder.DEV_OWNER_ID, "OWNER,SUPPORTER")),
            Map::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("SUPPORT_NOT_FOUND", response.body?.get("code"))
    }

    @Test
    @Order(12)
    fun `決済確定前の支援は取消できる`() {
        val response = rest.postForEntity(
            url("/api/v1/me/supports/$supportId/cancel"),
            HttpEntity("", supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        assertEquals("CANCELLED", dataOf(response.body)["status"])

        val second = rest.postForEntity(
            url("/api/v1/me/supports/$supportId/cancel"),
            HttpEntity("", supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, second.statusCode)
        assertEquals("SUPPORT_INVALID_STATE", second.body?.get("code"))
    }
}
