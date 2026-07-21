package com.example.cf.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File

/**
 * OpenAPI仕様の鮮度ゲート（詳細設計 §6.15/§13.4）。
 *
 * springdocが生成する実仕様（/v3/api-docs.yaml）が、コミット済みの `docs/api/openapi.yaml` と
 * 一致することを検証する。APIを変更したのにspecを更新し忘れた場合、このテストが失敗する。
 *
 * spec更新手順: アプリを起動して `curl -s localhost:8080/v3/api-docs.yaml -o docs/api/openapi.yaml`
 * （または本テスト失敗時に出力される `build/openapi-actual.yaml` をコミット先へコピー）。
 *
 * specは決定論的（info/servers固定・キーソート、OpenApiConfig + springdoc.writer-with-order-by-keys）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiSpecIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")

        /** リポジトリルートの docs/api/openapi.yaml（テスト作業ディレクトリは backend/）。 */
        private const val COMMITTED_SPEC = "../docs/api/openapi.yaml"
    }

    @LocalServerPort
    var port: Int = 0

    private val client = RestTemplate()

    @Test
    fun `生成されるOpenAPI仕様がコミット済みspecと一致する`() {
        // application/vnd.oai.openapi はcharset未指定でRestTemplateがISO-8859-1と解釈し日本語が化けるため、
        // バイト列で受け取りUTF-8で復号する。
        val generated = String(
            client.getForObject("http://localhost:$port/v3/api-docs.yaml", ByteArray::class.java)!!,
            Charsets.UTF_8,
        ).trim()

        val committedFile = File(COMMITTED_SPEC)
        assertTrue(
            committedFile.exists(),
            "コミット済みspecが見つかりません: ${committedFile.absolutePath}",
        )
        val committed = committedFile.readText().trim()

        if (generated != committed) {
            // 差分確認用に実仕様を出力する
            val actual = File("build/openapi-actual.yaml")
            actual.parentFile.mkdirs()
            actual.writeText(generated)
        }
        assertEquals(
            committed,
            generated,
            "OpenAPI仕様がコードと不一致です。docs/api/openapi.yaml を再生成してコミットしてください " +
                "（build/openapi-actual.yaml に実仕様を出力済み）。",
        )
    }
}
