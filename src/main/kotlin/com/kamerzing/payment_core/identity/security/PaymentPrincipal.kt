package com.kamerzing.payment_core.identity.security

import java.util.UUID

data class PaymentPrincipal(val userId: UUID, val role: String)
