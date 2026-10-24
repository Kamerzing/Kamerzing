package com.kamerzing.payment_core.payment

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import com.kamerzing.payment_core.payment.idempotency.IdempotencyDecision
import com.kamerzing.payment_core.shared.web.ApiException
import com.kamerzing.payment_core.shared.web.RequestTraceFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class PaymentCommandController(private val commands: PaymentCommandService) {
    @GetMapping("/payments/{id}")
    fun getPayment(@AuthenticationPrincipal principal: PaymentPrincipal, @PathVariable id: UUID): Map<String, Any?> =
        commands.getPayment(principal, id)

    @PostMapping("/admin/top-ups")
    fun topUp(
        @AuthenticationPrincipal principal: PaymentPrincipal,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody body: TopUpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any?>> = response(
        commands.topUp(principal, key, body, CommandResponseContext(request.requestURI, RequestTraceFilter.traceId(request))),
    )

    @PostMapping("/payments/transfers")
    fun transfer(
        @AuthenticationPrincipal principal: PaymentPrincipal,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody body: TransferRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any?>> = response(
        commands.transfer(principal, key, body, CommandResponseContext(request.requestURI, RequestTraceFilter.traceId(request))),
    )

    @PostMapping("/payments/{id}/refund")
    fun refund(
        @AuthenticationPrincipal principal: PaymentPrincipal,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) key: String?,
        @RequestBody(required = false) body: JsonNode?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any?>> {
        if (body != null && (!body.isObject || body.size() != 0)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Refund body must be an empty JSON object")
        }
        return response(commands.refund(
            principal, id, key, CommandResponseContext(request.requestURI, RequestTraceFilter.traceId(request)),
        ))
    }

    private fun response(result: IdempotencyDecision.Replay): ResponseEntity<Map<String, Any?>> {
        val builder = ResponseEntity.status(result.status)
        if (result.status == 201) {
            builder.contentType(MediaType.APPLICATION_JSON)
            builder.location(URI.create("/api/v1/payments/${result.paymentId}"))
        } else {
            builder.contentType(MediaType.APPLICATION_PROBLEM_JSON)
        }
        return builder.body(result.body)
    }
}
