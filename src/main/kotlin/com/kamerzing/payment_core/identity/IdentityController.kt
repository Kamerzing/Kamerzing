package com.kamerzing.payment_core.identity

import com.kamerzing.payment_core.identity.security.PaymentPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

data class Credentials(
    @field:NotBlank @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(max = 72) val password: String,
)
data class RefreshRequest(@field:NotBlank @field:Size(max = 128) val refreshToken: String)

@RestController
@RequestMapping("/api/v1")
class IdentityController(private val service: IdentityService) {
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody body: Credentials): UserView = service.register(body.email, body.password)

    @PostMapping("/auth/login")
    fun login(@Valid @RequestBody body: Credentials): TokenResponse = service.login(body.email, body.password)

    @PostMapping("/auth/refresh")
    fun refresh(@Valid @RequestBody body: RefreshRequest): TokenResponse = service.refresh(body.refreshToken)

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@Valid @RequestBody body: RefreshRequest) = service.logout(body.refreshToken)

    @GetMapping("/users/me")
    fun me(@AuthenticationPrincipal principal: PaymentPrincipal): UserView = service.me(principal.userId)
}
