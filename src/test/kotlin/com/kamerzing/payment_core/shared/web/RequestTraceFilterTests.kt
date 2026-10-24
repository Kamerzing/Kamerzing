package com.kamerzing.payment_core.shared.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTraceFilterTests {

    @Test
    fun `failure before MVC still releases MDC and is logged as an error response`() {
        val logger = LoggerFactory.getLogger(RequestTraceFilter::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val request = MockHttpServletRequest("GET", "/test/filter-failure")
        val response = MockHttpServletResponse()
        var traceInsideChain: String? = null
        try {
            assertFailsWith<ServletException> {
                RequestTraceFilter().doFilter(
                    request,
                    response,
                    FilterChain { _, _ ->
                        traceInsideChain = MDC.get(RequestTraceFilter.MDC_KEY)
                        throw ServletException("simulated failure before MVC")
                    },
                )
            }

            val traceId = response.getHeader(RequestTraceFilter.HEADER_NAME)
            UUID.fromString(traceId)
            assertEquals(traceId, traceInsideChain)
            assertNull(MDC.get(RequestTraceFilter.MDC_KEY))
            assertTrue(appender.list.single().formattedMessage.contains("status 500"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            MDC.remove(RequestTraceFilter.MDC_KEY)
        }
    }
}
