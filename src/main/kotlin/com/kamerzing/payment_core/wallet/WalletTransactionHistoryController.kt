package com.kamerzing.payment_core.wallet

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/wallets")
class WalletTransactionHistoryController(private val history: WalletTransactionHistoryService) {
    @GetMapping("/{id}/transactions")
    fun list(
        @AuthenticationPrincipal principal: PaymentPrincipal,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): WalletTransactionPage = history.list(principal, id, page, size)
}