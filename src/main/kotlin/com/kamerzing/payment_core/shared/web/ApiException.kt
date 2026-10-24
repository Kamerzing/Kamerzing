package com.kamerzing.payment_core.shared.web

import org.springframework.http.HttpStatus

/** A deliberate public API failure; safeDetail must never contain internal exception data. */
class ApiException(val status: HttpStatus, val code: String, val safeDetail: String) : RuntimeException(safeDetail)
