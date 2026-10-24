package com.kamerzing.payment_core.shared.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

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
        val problem = ApiProblems.create(statusCode, code.name, code.detail, servletRequest)
        return ResponseEntity(problem, headers, statusCode)
    }

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException, request: jakarta.servlet.http.HttpServletRequest): ResponseEntity<Any> =
        ResponseEntity.status(ex.status)
            .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
            .body(ApiProblems.create(ex.status, ex.code, ex.safeDetail, request))

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(ex: Exception, request: WebRequest): ResponseEntity<Any>? =
        handleExceptionInternal(ex, null, HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR, request)
}
