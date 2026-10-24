package com.kamerzing.payment_core.ledger

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin")
class ReconciliationController(private val reconciliation: ReconciliationService) {
    @GetMapping("/reconciliation")
    fun get(@AuthenticationPrincipal principal: PaymentPrincipal): ReconciliationReport =
        reconciliation.reconcile(principal)
}