package com.kamerzing.payment_core.shared.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI
import java.util.Locale

object ApiProblems {
    fun create(status: HttpStatusCode, code: String, detail: String, request: HttpServletRequest): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("urn:payment-core:problem:${code.lowercase(Locale.ROOT).replace('_', '-')}")
            title = HttpStatus.resolve(status.value())?.reasonPhrase ?: "Request failed"
            instance = URI.create(request.requestURI)
            setProperty("code", code)
            setProperty("traceId", RequestTraceFilter.traceId(request))
        }
}
