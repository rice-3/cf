package com.example.cf.integration

import com.example.cf.config.DevUserSeeder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 工程9 Identity/Admin/Audit の結合テスト（API-US-001/002, API-AD-001〜003, API-AU-001/002）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IdentityAdminAuditIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")
    }

    @LocalServerPort
    var port: Int = 0

    private val rest = RestTemplate(JdkClientHttpRequestFactory()).apply {
        errorHandler = object : DefaultResponseErrorHandler() {
            override fun hasError(response: ClientHttpResponse): Boolean = false
        }
    }

    private fun url(path: String) = "http://localhost:$port$path"

    private val rangeStart: Instant = Instant.now().minus(1, ChronoUnit.HOURS)

    private fun headers(userId: String, roles: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Dev-User", userId)
        set("X-Dev-Roles", roles)
    }

    private fun supporterHeaders() = headers(DevUserSeeder.DEV_SUPPORTER_ID, "SUPPORTER")

    private fun adminHeaders() = headers(DevUserSeeder.DEV_ADMIN_ID, "ADMIN,OPERATOR,AUDITOR")

    /** 純粋なAUDITOR（ADMIN権限なし）を模擬する。Devヘッダー認証はDBロールと無関係にヘッダーを信頼する。 */
    private fun auditorOnlyHeaders() = headers(DevUserSeeder.DEV_REVIEWER_ID, "AUDITOR")

    @Suppress("UNCHECKED_CAST")
    private fun dataOf(body: Map<*, *>?) = body?.get("data") as Map<String, Any?>

    // ---- API-US-001/002 -----------------------------------------------------

    @Test
    @Order(1)
    fun `自分のプロフィールを取得できる`() {
        val response = rest.exchange(
            url("/api/v1/me"),
            HttpMethod.GET,
            HttpEntity<Void>(supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        val data = dataOf(response.body)
        assertEquals(DevUserSeeder.DEV_SUPPORTER_ID, data["userId"])
        assertEquals(listOf("SUPPORTER"), data["roles"])
        assertEquals(0, (data["version"] as Number).toInt())
    }

    @Test
    @Order(2)
    fun `version不一致のプロフィール更新は409になる`() {
        val body = """{"displayName":"更新後の名前","email":"supporter-updated@example.invalid","expectedVersion":99}"""
        val response = rest.exchange(
            url("/api/v1/me"),
            HttpMethod.PUT,
            HttpEntity(body, supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("OPTIMISTIC_LOCK_CONFLICT", response.body?.get("code"))
    }

    @Test
    @Order(3)
    fun `既に使われているメールへの更新は409 EMAIL_ALREADY_USEDになる`() {
        val body = """{"displayName":"更新後の名前","email":"admin@example.invalid","expectedVersion":0}"""
        val response = rest.exchange(
            url("/api/v1/me"),
            HttpMethod.PUT,
            HttpEntity(body, supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("EMAIL_ALREADY_USED", response.body?.get("code"))
    }

    @Test
    @Order(4)
    fun `正しいversionでプロフィールを更新できる`() {
        val body = """{"displayName":"更新後の名前","email":"supporter-updated@example.invalid","expectedVersion":0}"""
        val response = rest.exchange(
            url("/api/v1/me"),
            HttpMethod.PUT,
            HttpEntity(body, supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        val data = dataOf(response.body)
        assertEquals("更新後の名前", data["displayName"])
        assertEquals(1, (data["version"] as Number).toInt())
    }

    // ---- API-AD-001 ----------------------------------------------------------

    @Test
    @Order(10)
    fun `ADMIN以外は会員検索できない`() {
        val response = rest.exchange(
            url("/api/v1/admin/users"),
            HttpMethod.GET,
            HttpEntity<Void>(supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    @Order(11)
    fun `ADMINは会員検索できる`() {
        val response = rest.exchange(
            url("/api/v1/admin/users?size=50"),
            HttpMethod.GET,
            HttpEntity<Void>(adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        val data = dataOf(response.body)

        @Suppress("UNCHECKED_CAST")
        val items = data["items"] as List<Map<String, Any?>>
        assertTrue(items.any { it["userId"] == DevUserSeeder.DEV_SUPPORTER_ID }, "seeded users should be included")
    }

    // ---- API-AD-002 ----------------------------------------------------------

    @Test
    @Order(20)
    fun `ADMINが自分のADMINロールを剥奪しようとすると403になる`() {
        val body = """{"roles":["OPERATOR","AUDITOR"],"expectedVersion":0,"reason":"テスト"}"""
        val response = rest.exchange(
            url("/api/v1/admin/users/${DevUserSeeder.DEV_ADMIN_ID}/roles"),
            HttpMethod.PUT,
            HttpEntity(body, adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("ROLE_UPDATE_FORBIDDEN", response.body?.get("code"))
    }

    @Test
    @Order(21)
    fun `version不一致のロール更新は409になる`() {
        val body = """{"roles":["OWNER","REVIEWER"],"expectedVersion":99,"reason":"テスト"}"""
        val response = rest.exchange(
            url("/api/v1/admin/users/${DevUserSeeder.DEV_OWNER_ID}/roles"),
            HttpMethod.PUT,
            HttpEntity(body, adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("OPTIMISTIC_LOCK_CONFLICT", response.body?.get("code"))
    }

    @Test
    @Order(22)
    fun `ADMINは他の利用者のロールを更新できる`() {
        val body = """{"roles":["OWNER","REVIEWER"],"expectedVersion":0,"reason":"審査担当も兼任させる"}"""
        val response = rest.exchange(
            url("/api/v1/admin/users/${DevUserSeeder.DEV_OWNER_ID}/roles"),
            HttpMethod.PUT,
            HttpEntity(body, adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        val data = dataOf(response.body)
        assertEquals(setOf("OWNER", "REVIEWER"), (data["roles"] as List<*>).toSet())
        assertEquals(1, (data["version"] as Number).toInt())
    }

    @Test
    @Order(23)
    fun `割当不可なロールの指定は400になる`() {
        val body = """{"roles":["GUEST"],"expectedVersion":1,"reason":"テスト"}"""
        val response = rest.exchange(
            url("/api/v1/admin/users/${DevUserSeeder.DEV_OWNER_ID}/roles"),
            HttpMethod.PUT,
            HttpEntity(body, adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    // ---- API-AD-003 ----------------------------------------------------------

    @Test
    @Order(30)
    fun `ADMINは自分自身を停止できない`() {
        val body = """{"expectedVersion":0}"""
        val response = rest.exchange(
            url("/api/v1/admin/users/${DevUserSeeder.DEV_ADMIN_ID}/suspend"),
            HttpMethod.POST,
            HttpEntity(body, adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
        assertEquals("USER_SUSPEND_FORBIDDEN", response.body?.get("code"))
    }

    @Test
    @Order(31)
    fun `ADMINは他の利用者を停止できる`() {
        // OWNERはOrder(22)のロール更新で既にversion=1になっている
        val body = """{"expectedVersion":1,"reason":"規約違反のため"}"""
        val response = rest.exchange(
            url("/api/v1/admin/users/${DevUserSeeder.DEV_OWNER_ID}/suspend"),
            HttpMethod.POST,
            HttpEntity(body, adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        assertEquals("SUSPENDED", dataOf(response.body)["status"])
    }

    @Test
    @Order(32)
    fun `停止済みの利用者を再度停止すると409になる`() {
        val body = """{"expectedVersion":1}"""
        val response = rest.exchange(
            url("/api/v1/admin/users/${DevUserSeeder.DEV_OWNER_ID}/suspend"),
            HttpMethod.POST,
            HttpEntity(body, adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("USER_INVALID_STATE", response.body?.get("code"))
    }

    // ---- API-AU-001/002 --------------------------------------------------------

    @Test
    @Order(40)
    fun `ADMIN AUDITOR以外は監査ログを検索できない`() {
        val response = rest.exchange(
            url("/api/v1/audit-logs?from=$rangeStart&to=${Instant.now()}"),
            HttpMethod.GET,
            HttpEntity<Void>(supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    @Order(41)
    fun `日付範囲が31日を超える場合は400になる`() {
        val from = rangeStart.minus(40, ChronoUnit.DAYS)
        val response = rest.exchange(
            url("/api/v1/audit-logs?from=$from&to=${Instant.now()}"),
            HttpMethod.GET,
            HttpEntity<Void>(adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("DATE_RANGE_TOO_LARGE", response.body?.get("code"))
    }

    @Test
    @Order(42)
    fun `AUDITOR単独でも監査ログを検索でき役割変更・停止操作が記録されている`() {
        val response = rest.exchange(
            url("/api/v1/audit-logs?from=$rangeStart&to=${Instant.now()}&size=100"),
            HttpMethod.GET,
            HttpEntity<Void>(auditorOnlyHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        val data = dataOf(response.body)

        @Suppress("UNCHECKED_CAST")
        val items = data["items"] as List<Map<String, Any?>>
        val actions = items.map { it["action"] }
        assertTrue(actions.contains("USER_ROLE_UPDATE"), "actions=$actions")
        assertTrue(actions.contains("USER_SUSPEND"), "actions=$actions")
        assertTrue(actions.contains("PROFILE_UPDATE"), "actions=$actions")
    }

    @Test
    @Order(43)
    fun `AI利用記録検索は空でも200になる`() {
        val response = rest.exchange(
            url("/api/v1/ai-activities?from=$rangeStart&to=${Instant.now()}"),
            HttpMethod.GET,
            HttpEntity<Void>(adminHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")
        val data = dataOf(response.body)
        assertEquals(0, (data["totalElements"] as Number).toInt())
    }
}
