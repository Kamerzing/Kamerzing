package com.kamerzing.payment_core.shared.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI
import java.util.Locale

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val errorLogger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (statusCode.is5xxServerError) {
            // Exception messages can contain SQL parameters or credentials.
            errorLogger.error("Request failed with HTTP {} ({})", statusCode.value(), ex.javaClass.name)
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request)
    }

    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val servletRequest = (request as ServletWebRequest).request
        val code = ApiErrorCode.forStatus(statusCode)
        val problem = ProblemDetail.forStatusAndDetail(statusCode, code.detail).apply {
            type = URI.create("urn:payment-core:problem:${code.name.lowercase(Locale.ROOT).replace('_', '-')}")
            title = HttpStatus.resolve(statusCode.value())?.reasonPhrase ?: "Request failed"
            instance = URI.create(servletRequest.requestURI)
            setProperty("code", code.name)
            setProperty("traceId", RequestTraceFilter.traceId(servletRequest))
        }
        return ResponseEntity(problem, headers, statusCode)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(ex: Exception, request: WebRequest): ResponseEntity<Any>? =
        handleExceptionInternal(ex, null, HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR, request)
}
