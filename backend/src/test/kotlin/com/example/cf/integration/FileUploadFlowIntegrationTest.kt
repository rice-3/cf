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
 * ファイルアップロードフローの結合テスト（API-FL-001/002、詳細設計 §10.2）。
 * S3はStubFileStorageAdapter（ADR-006: 教育環境Mock）を使用する。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FileUploadFlowIntegrationTest {

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

    private var fileId: String = ""

    private fun headers(userId: String, roles: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set("X-Dev-User", userId)
        set("X-Dev-Roles", roles)
    }

    private fun ownerHeaders() = headers(DevUserSeeder.DEV_OWNER_ID, "OWNER,SUPPORTER")

    private fun supporterHeaders() = headers(DevUserSeeder.DEV_SUPPORTER_ID, "SUPPORTER")

    private fun issueBody(
        contentType: String = "image/jpeg",
        size: Long = 204_800,
        sha256: String = SHA256,
    ): String = """
        {
          "purpose": "PROJECT_MAIN",
          "fileName": "メイン画像.jpg",
          "contentType": "$contentType",
          "size": $size,
          "sha256": "$sha256"
        }
    """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun dataOf(body: Map<*, *>?): Map<String, Any?> = body?.get("data") as Map<String, Any?>

    @Test
    @Order(1)
    fun `未認証はアップロードURLを発行できない`() {
        val response = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(issueBody(), HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
            Map::class.java,
        )
        assertTrue(
            response.statusCode == HttpStatus.UNAUTHORIZED || response.statusCode == HttpStatus.FORBIDDEN,
            "expected 401/403 but was ${response.statusCode}",
        )
    }

    @Test
    @Order(2)
    fun `認証済み利用者はアップロードURLを発行できる`() {
        val response = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(issueBody(), ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CREATED, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        fileId = data["fileId"] as String
        assertNotNull(data["uploadUrl"])
        assertNotNull(data["expiresAt"])
        // Content-TypeはPUT必須ヘッダーへ含まれる（§10.2 署名条件）
        val headers = data["headers"] as Map<*, *>
        assertEquals("image/jpeg", headers["Content-Type"])
    }

    @Test
    @Order(3)
    fun `許可外MIMEは400 FILE_TYPE_NOT_ALLOWED`() {
        val response = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(issueBody(contentType = "application/x-msdownload"), ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("FILE_TYPE_NOT_ALLOWED", response.body?.get("code"))
    }

    @Test
    @Order(4)
    fun `10MB超は400 FILE_TOO_LARGE`() {
        val response = rest.postForEntity(
            url("/api/v1/files/presigned-uploads"),
            HttpEntity(issueBody(size = 10L * 1024 * 1024 + 1), ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("FILE_TOO_LARGE", response.body?.get("code"))
    }

    @Test
    @Order(5)
    fun `sha256不一致の完了は409 FILE_METADATA_MISMATCH`() {
        val response = rest.postForEntity(
            url("/api/v1/files/$fileId/complete"),
            HttpEntity("""{"sha256": "${"b".repeat(64)}"}""", ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("FILE_METADATA_MISMATCH", response.body?.get("code"))
    }

    @Test
    @Order(6)
    fun `他利用者からの完了は404になる`() {
        val response = rest.postForEntity(
            url("/api/v1/files/$fileId/complete"),
            HttpEntity("""{"sha256": "$SHA256"}""", supporterHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("FILE_NOT_FOUND", response.body?.get("code"))
    }

    @Test
    @Order(7)
    fun `所有者は完了できCOMPLETEになる`() {
        val response = rest.postForEntity(
            url("/api/v1/files/$fileId/complete"),
            HttpEntity("""{"sha256": "$SHA256"}""", ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode, "body=${response.body}")

        val data = dataOf(response.body)
        assertEquals("COMPLETE", data["status"])
        assertTrue((data["downloadReference"] as String).startsWith("s3://"), "downloadReference=$data")
    }

    @Test
    @Order(8)
    fun `同一ハッシュの完了再実行は冪等に200`() {
        val response = rest.postForEntity(
            url("/api/v1/files/$fileId/complete"),
            HttpEntity("""{"sha256": "$SHA256"}""", ownerHeaders()),
            Map::class.java,
        )
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("COMPLETE", dataOf(response.body)["status"])
    }

    @Test
    @Order(9)
    fun `file_objectと監査ログが記録されている`() {
        val status = jdbcTemplate.queryForObject(
            "select status from file_object where file_id = ?",
            String::class.java,
            fileId,
        )
        assertEquals("COMPLETE", status)

        val expires = jdbcTemplate.queryForList(
            "select expires_at from file_object where file_id = ?",
            fileId,
        ).first()["expires_at"]
        assertEquals(null, expires, "COMPLETE後はexpires_atが解除される")

        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from audit_log where resource_type = 'File' and resource_id = ?",
            Int::class.java,
            fileId,
        ) ?: 0
        assertTrue(auditCount >= 2, "audit_log should contain issue/complete records: $auditCount")
    }
}
