package com.kamerzing.payment_core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PaymentCoreApplication

fun main(args: Array<String>) {
	runApplication<PaymentCoreApplication>(*args)
}
