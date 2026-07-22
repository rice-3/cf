package com.example.cf.shared.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * メトリクス共通設定（詳細設計 §12.5–12.6/§9.3）。
 *
 * 全メーターへ `application` タグを付与し、複数サービス/環境の識別を可能にする。
 * ビジネスメトリクスは [BusinessMetrics]（滞留・失敗の点在状態）と [BatchMetrics]（バッチ稼働）で登録し、
 * APIレイテンシ/5xx率は actuator の `http.server.requests`（`application.yml` でヒストグラム有効化）で収集する。
 */
@Configuration
class ObservabilityConfig {

    @Bean
    fun commonMetricsTags(
        @Value("\${spring.application.name:cf-api}") applicationName: String,
    ): MeterRegistryCustomizer<MeterRegistry> = MeterRegistryCustomizer { registry ->
        registry.config().meterFilter(MeterFilter.commonTags(listOf(io.micrometer.core.instrument.Tag.of("application", applicationName))))
    }
}
