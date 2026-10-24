package com.kamerzing.payment_core.identity.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration(proxyBeanMethods = false)
class PasswordConfiguration {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    // No generated in-memory user and no password printed by Boot at startup.
    @Bean
    fun userDetailsService(): UserDetailsService = UserDetailsService {
        throw UsernameNotFoundException("Password authentication is available through the JSON login endpoint")
    }
}
