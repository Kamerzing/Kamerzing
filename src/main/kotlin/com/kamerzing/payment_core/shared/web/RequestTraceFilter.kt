package com.kamerzing.payment_core.shared.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTraceFilter : OncePerRequestFilter() {

    private val requestLogger = LoggerFactory.getLogger(RequestTraceFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = traceId(request)
        val previousTraceId = MDC.get(MDC_KEY)
        val startedAt = System.nanoTime()
        var completed = false
        response.setHeader(HEADER_NAME, traceId)
        MDC.put(MDC_KEY, traceId)

        try {
            filterChain.doFilter(request, response)
            completed = true
        } finally {
            // Log no body, query string, credentials, or client-supplied correlation value.
            requestLogger.info(
                "HTTP {} {} completed with status {} in {} ms",
                request.method,
                request.requestURI,
                if (completed) response.status else HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                (System.nanoTime() - startedAt) / 1_000_000,
            )
            if (previousTraceId == null) {
                MDC.remove(MDC_KEY)
            } else {
                MDC.put(MDC_KEY, previousTraceId)
            }
        }
    }

    companion object {
        const val HEADER_NAME = "X-Request-Id"
        const val MDC_KEY = "traceId"
        private const val ATTRIBUTE_NAME = "com.kamerzing.payment_core.traceId"

        internal fun traceId(request: HttpServletRequest): String =
            (request.getAttribute(ATTRIBUTE_NAME) as? String)
                ?: UUID.randomUUID().toString().also { request.setAttribute(ATTRIBUTE_NAME, it) }
    }
}
