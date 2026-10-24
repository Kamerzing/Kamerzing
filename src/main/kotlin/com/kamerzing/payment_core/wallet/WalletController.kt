package com.kamerzing.payment_core.wallet

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

data class CreateWalletRequest(val currency: String)

@RestController
@RequestMapping("/api/v1/wallets")
class WalletController(private val wallets: WalletService) {
    @PostMapping
    fun create(@AuthenticationPrincipal principal: PaymentPrincipal, @RequestBody request: CreateWalletRequest): ResponseEntity<WalletView> {
        val wallet = wallets.create(principal.userId, request.currency)
        return ResponseEntity.created(URI.create("/api/v1/wallets/${wallet.id}")).body(wallet)
    }

    @GetMapping("/me")
    fun mine(@AuthenticationPrincipal principal: PaymentPrincipal): List<WalletView> = wallets.listMine(principal.userId)

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal principal: PaymentPrincipal, @PathVariable id: UUID): WalletView =
        wallets.getMine(principal.userId, id)
}
