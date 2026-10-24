package com.kamerzing.payment_core.identity

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import org.springframework.web.context.WebApplicationContext

/** Explicit one-shot process; never upgrades an existing USER account. */
@Component
@ConditionalOnProperty(name = ["payment.auth.bootstrap-admin"], havingValue = "true")
class BootstrapAdminCommand(
    private val service: IdentityService,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        check(context !is WebApplicationContext) { "Admin bootstrap requires --spring.main.web-application-type=none" }
        val email = System.getenv("BOOTSTRAP_ADMIN_EMAIL")
            ?: error("BOOTSTRAP_ADMIN_EMAIL environment variable is required")
        val password = System.getenv("BOOTSTRAP_ADMIN_PASSWORD")
            ?: error("BOOTSTRAP_ADMIN_PASSWORD environment variable is required")
        service.bootstrapAdmin(email, password)
        LoggerFactory.getLogger(javaClass).info("Administrator created successfully")
        context.close()
    }
}
