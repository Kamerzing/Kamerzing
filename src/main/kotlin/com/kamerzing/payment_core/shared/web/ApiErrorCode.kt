package com.kamerzing.payment_core.shared.web

import org.springframework.http.HttpStatusCode

enum class ApiErrorCode(val detail: String) {
    VALIDATION_ERROR("The request contains invalid data."),
    UNAUTHENTICATED("Authentication is required."),
    FORBIDDEN("This action is not permitted."),
    RESOURCE_NOT_FOUND("The requested resource was not found."),
    METHOD_NOT_ALLOWED("The HTTP method is not supported for this resource."),
    NOT_ACCEPTABLE("The requested response format is not supported."),
    PAYLOAD_TOO_LARGE("The request payload is too large."),
    UNSUPPORTED_MEDIA_TYPE("The request content type is not supported."),
    TEMPORARILY_UNAVAILABLE("The service is temporarily unavailable."),
    INTERNAL_ERROR("An unexpected error occurred."),
    ;

    companion object {
        fun forStatus(status: HttpStatusCode): ApiErrorCode = when (status.value()) {
            400 -> VALIDATION_ERROR
            401 -> UNAUTHENTICATED
            403 -> FORBIDDEN
            404 -> RESOURCE_NOT_FOUND
            405 -> METHOD_NOT_ALLOWED
            406 -> NOT_ACCEPTABLE
            413 -> PAYLOAD_TOO_LARGE
            415 -> UNSUPPORTED_MEDIA_TYPE
            503 -> TEMPORARILY_UNAVAILABLE
            else -> if (status.is4xxClientError) VALIDATION_ERROR else INTERNAL_ERROR
        }
    }
}
