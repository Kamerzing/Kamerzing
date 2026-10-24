package com.kamerzing.payment_core

import com.kamerzing.payment_core.support.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(PostgresTestConfiguration::class)
class PaymentCoreApplicationTests {

	@Test
	fun contextLoads() {
	}

}
