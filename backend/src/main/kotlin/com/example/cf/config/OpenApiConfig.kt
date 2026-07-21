package com.example.cf.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI仕様のメタ情報（詳細設計 §6.15）。
 *
 * 生成specを決定論的な成果物にするため、info とservers を固定する。
 * - servers はホスト依存URLを排除し相対 "/" に固定（環境差で差分が出ないようにする）。
 * - キー順の安定化は application.yml の `springdoc.writer-with-order-by-keys=true` で行う。
 *
 * 生成されたspecは `docs/api/openapi.yaml` にコミットし、CIで整合性・互換性を検証する。
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun cfTrainingOpenApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("CF-Training API")
                .version("v1")
                .description("クラウドファンディング型教育・実践開発システムのREST API（§6 API基本設計）"),
        )
        .servers(listOf(Server().url("/")))
}
