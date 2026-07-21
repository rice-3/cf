package com.example.cf.shared.web

import com.example.cf.shared.kernel.id.CorrelationId
import java.time.Instant

/** 正常応答の共通形式（基本設計 §6.2）。 */
data class ApiMeta(
    val correlationId: String,
    val timestamp: Instant,
)

data class ApiEnvelope<T>(
    val data: T,
    val meta: ApiMeta,
)

fun <T> T.toEnvelope(correlationId: CorrelationId, now: Instant): ApiEnvelope<T> = ApiEnvelope(this, ApiMeta(correlationId.value, now))
