package com.example.cf.shared.web

import com.example.cf.shared.kernel.id.CorrelationId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 相関IDフィルタ（基本設計 §6.1）。
 * `X-Correlation-Id` 未指定時はサーバー採番し、応答ヘッダーとMDCへ設定する。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val raw = request.getHeader(HEADER)?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: "cor_${UUID.randomUUID()}"
        val correlationId = CorrelationId(raw)

        request.setAttribute(ATTRIBUTE, correlationId)
        response.setHeader(HEADER, correlationId.value)
        MDC.put(MDC_KEY, correlationId.value)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-Id"
        const val ATTRIBUTE = "cf.correlationId"
        const val MDC_KEY = "correlationId"

        fun from(request: HttpServletRequest): CorrelationId = request.getAttribute(ATTRIBUTE) as? CorrelationId
            ?: CorrelationId("cor_${UUID.randomUUID()}")
    }
}
