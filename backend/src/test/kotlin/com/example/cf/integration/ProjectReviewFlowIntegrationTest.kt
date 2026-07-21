package com.example.cf.integration

import com.example.cf.config.DevUserSeeder
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

/**
 * プロジェクト作成〜審査承認の結合テスト（E2E-001/002相当、詳細設計 §14.1 Web Integration）。
 * Testcontainers PostgreSQL上でFlyway・JPA・Security・APIを検証する。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ProjectReviewFlowIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")
    }

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    /** ステータスコード検証のため4xx/5xxでも例外を投げないクライアント。 */
    // 401応答の本文を検証できるようJDK HttpClientベースのFactoryを使用する
    // （既定のHttpURLConnectionは401の本文を破棄する）
    private val rest = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(response: ClientHttpResponse): Boolean = false
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private var projectId: String = ""
    private var reviewId: String = ""
    private var mainFileId: String = ""

    private fun headers(userId: String, roles: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Dev-User", userId)
        set("X-Dev-Roles", roles)
    }

    private fun ownerHeaders() = headers(DevUserSeeder.DEV_OWNER_ID, "OWNER,SUPPORTER")

    private fun reviewerHeaders() = headers(DevUserSeeder.DEV_REVIEWER_ID, "REVIEWER")

    private fun createBody(): String = """
        {
          "title": "教育用テストプロジェクト",
          "summary": "結合テスト用の合成データです。",
          "body": "本文テキスト。",
          "targetAmount": 500000,
          "fundingType": "ALL_OR_NOTHING",
          "startAt": "2027-01-01T00:00:00Z",
          "endAt": "2027-02-01T00:00:00Z",
          "mainFileId": "$mainFileId",
          "rewardPlans": [
            {"name": "お礼メール", "description": "感謝のメールをお送りします。", "unitAmount": 3000, "quantityLimit": 100, "displayOrder": 1}
          ]
        }
    """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun dataOf(body: Map<*, *>?): Map<String, Any?> = body?.get("data") as Map<String, Any?>

    @Test
    @Order(1)
    fun `未認証で起案者APIは401または403になる`() {
        val response = rest.postForEntity(
            url("/api/v1/owner/projects"),
            HttpEntity(createBody(), HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            Map::class.java,
        )
        assertTrue(
            response.statusCode == HttpStatus.UNAUTHORIZED || response.statusCode == HttpStatus.FORBIDDEN,
            "expected 401/403 but was ${response.statusCode}",
        )
    }

    @Test
    @Order(2)
    fun `起案者が下書きを作成できる`() {
        // メイン画像はFile API（API-FL-001/002）で実際にCOMPLETEへ遷移させる
        val sha = "0123456789abcdef".repeat(4)
        val issue = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(
                """{"purpose":"PROJECT_MAIN","fileName":"main.jpg","contentType":"image/jpeg","size":204800,"sha256":"$sha"}""",
                ownerHeaders(),
            ),
            Map::class.java,
        )
        assertEquals(HttpStatus.CREATED, issue.statusCode, "body=${issue.body}")
        mainFileId = dataOf(issue.body)["fileId"] as String

        val complete = rest.postForEntity(
            url("/api/v1/files/$mainFileId/complete"),
            HttpEntity("""{"sha256":"$sha"}""", ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, complete.statusCode, "body=${complete.body}")

        val response = rest.postForEntity(
            url("/api/v1/owner/projects"),
            HttpEntity(createBody(), ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CREATED, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        projectId = data["projectId"] as String
        assertEquals("DRAFT", data["status"])
        assertNotNull(response.headers.location)
    }

    @Test
    @Order(3)
    fun `検証エラーは400 Problem Detailsになる`() {
        val invalid = createBody().replace("500000", "999")
        val response = rest.postForEntity(
            url("/api/v1/owner/projects"),
            HttpEntity(invalid, ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("VALIDATION_ERROR", response.body?.get("code"))
        assertNotNull(response.body?.get("correlationId"))
    }

    @Test
    @Order(4)
    fun `version不一致の更新は409になる`() {
        val update = """
            {
              "expectedVersion": 99,
              "title": "更新タイトル",
              "summary": "更新概要",
              "body": "更新本文",
              "targetAmount": 500000,
              "fundingType": "ALL_OR_NOTHING",
              "startAt": "2027-01-01T00:00:00Z",
              "endAt": "2027-02-01T00:00:00Z",
              "rewardPlans": [
                {"name": "お礼メール", "description": "説明", "unitAmount": 3000, "displayOrder": 1}
              ]
            }
        """.trimIndent()
        val response = rest.exchange(
            url("/api/v1/owner/projects/$projectId"),
            HttpMethod.PUT,
            HttpEntity(update, ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("OPTIMISTIC_LOCK_CONFLICT", response.body?.get("code"))
    }

    @Test
    @Order(5)
    fun `審査申請できる`() {
        val submit = """
            {"expectedVersion": 0, "confirmations": ["TERMS_ACCEPTED", "CONTENT_RESPONSIBILITY_ACCEPTED"]}
        """.trimIndent()
        val response = rest.postForEntity(
            url("/api/v1/owner/projects/$projectId/review-requests"),
            HttpEntity(submit, ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.ACCEPTED, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        reviewId = data["reviewId"] as String
        assertEquals("REVIEW_REQUESTED", data["projectStatus"])
    }

    @Test
    @Order(6)
    fun `審査申請済みプロジェクトは再申請できず409になる`() {
        val submit = """
            {"expectedVersion": 1, "confirmations": ["TERMS_ACCEPTED", "CONTENT_RESPONSIBILITY_ACCEPTED"]}
        """.trimIndent()
        val response = rest.postForEntity(
            url("/api/v1/owner/projects/$projectId/review-requests"),
            HttpEntity(submit, ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    @Order(7)
    fun `起案者は審査APIへアクセスできない`() {
        val response = rest.postForEntity(
            url("/api/v1/reviews/$reviewId/start"),
            HttpEntity("", ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    @Order(8)
    fun `審査担当者が審査を開始できる`() {
        val response = rest.postForEntity(
            url("/api/v1/reviews/$reviewId/start"),
            HttpEntity("{}", reviewerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        assertEquals("UNDER_REVIEW", data["reviewStatus"])
        assertEquals("UNDER_REVIEW", data["projectStatus"])
    }

    @Test
    @Order(9)
    fun `チェックリスト未完了の承認は422になる`() {
        val approve = """
            {"expectedVersion": 1, "checklist": {"CONTENT_CONFIRMED": true}}
        """.trimIndent()
        val response = rest.postForEntity(
            url("/api/v1/reviews/$reviewId/approve"),
            HttpEntity(approve, reviewerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.statusCode)
        assertEquals("REVIEW_CHECKLIST_INCOMPLETE", response.body?.get("code"))
    }

    @Test
    @Order(10)
    fun `チェックリスト完了で承認できる`() {
        val approve = """
            {
              "expectedVersion": 1,
              "checklist": {
                "CONTENT_CONFIRMED": true,
                "LEGAL_CONFIRMED": true,
                "REWARD_CONFIRMED": true,
                "PERIOD_CONFIRMED": true
              },
              "comment": "問題ありません。"
            }
        """.trimIndent()
        val response = rest.postForEntity(
            url("/api/v1/reviews/$reviewId/approve"),
            HttpEntity(approve, reviewerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        assertEquals("APPROVED", data["reviewStatus"])
        assertEquals("APPROVED", data["projectStatus"])
    }

    @Test
    @Order(11)
    fun `監査ログとOutboxと履歴が記録されている`() {
        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where resource_id = ?",
            Int::class.java,
            projectId,
        ) ?: 0
        assertTrue(auditCount >= 2, "audit_log should contain create/submit records: $auditCount")

        val outboxCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_event where aggregate_id = ?",
            Int::class.java,
            projectId,
        ) ?: 0
        assertTrue(outboxCount >= 3, "outbox should contain created/submitted/started/approved: $outboxCount")

        val historyCount = jdbcTemplate.queryForObject(
            "select count(*) from project_status_history where project_id = ?",
            Int::class.java,
            projectId,
        ) ?: 0
        assertTrue(historyCount >= 3, "status history should be recorded: $historyCount")

        val reviewHistoryCount = jdbcTemplate.queryForObject(
            "select count(*) from review_history where review_id = ?",
            Int::class.java,
            reviewId,
        ) ?: 0
        assertTrue(reviewHistoryCount >= 2, "review history should contain START/APPROVE: $reviewHistoryCount")
    }

    @Test
    @Order(12)
    fun `非公開プロジェクトは第三者から404になり所有者からは参照できる`() {
        // APPROVED（未公開）状態のプロジェクトへ未認証でアクセス
        val anonymous = rest.getForEntity(url("/api/v1/projects/$projectId"), Map::class.java)
        assertEquals(HttpStatus.NOT_FOUND, anonymous.statusCode)

        // 所有者からは参照できる
        val asOwner = rest.exchange(
            url("/api/v1/projects/$projectId"),
            HttpMethod.GET,
            HttpEntity<Void>(ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, asOwner.statusCode)
    }

    @Test
    @Order(13)
    fun `キーワード未指定の公開プロジェクト検索は200になる（API-PJ-001）`() {
        // keywordパラメータを渡さない場合、JPQL側のnullパラメータ型推論起因で
        // 「character varying ~~ bytea」エラーが発生していた回帰防止（フロントSCR-010の既定動作）。
        val response = rest.getForEntity(url("/api/v1/projects?page=0&size=5"), Map::class.java)
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
    }
}
