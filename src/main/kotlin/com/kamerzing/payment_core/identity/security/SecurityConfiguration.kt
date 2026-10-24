package com.kamerzing.payment_core.identity.security

import com.kamerzing.payment_core.identity.IdentityService
import com.kamerzing.payment_core.shared.web.ApiProblems
import com.kamerzing.payment_core.shared.web.RequestTraceFilter
import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity, identities: IdentityService, tokens: TokenService, mapper: JsonMapper): SecurityFilterChain {
        val problems = SecurityProblems(mapper)
        return http
            // Credentials are only explicit Bearer headers / JSON refresh tokens, never cookies.
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ -> problems.write(request, response, HttpStatus.UNAUTHORIZED) }
                    .accessDeniedHandler { request, response, _ -> problems.write(request, response, HttpStatus.FORBIDDEN) }
            }
            .addFilterBefore(BearerAuthenticationFilter(identities, tokens, problems), UsernamePasswordAuthenticationFilter::class.java)
            .build()
    }
}

internal class SecurityProblems(private val mapper: JsonMapper) {
    fun write(request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus) {
        val (code, detail) = when (status) {
            HttpStatus.UNAUTHORIZED -> "UNAUTHENTICATED" to "Valid authentication is required"
            HttpStatus.FORBIDDEN -> "FORBIDDEN" to "This operation is not permitted"
            else -> "INTERNAL_ERROR" to "An unexpected error occurred"
        }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader(RequestTraceFilter.HEADER_NAME, RequestTraceFilter.traceId(request))
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer")
        val problem = ApiProblems.create(status, code, detail, request)
        mapper.writeValue(response.outputStream, mapOf(
            "type" to problem.type.toString(),
            "title" to problem.title,
            "status" to problem.status,
            "detail" to problem.detail,
            "instance" to problem.instance.toString(),
            "code" to code,
            "traceId" to RequestTraceFilter.traceId(request),
        ))
    }
}

internal class BearerAuthenticationFilter(
    private val identities: IdentityService,
    private val tokens: TokenService,
    private val problems: SecurityProblems,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "POST" && request.servletPath in setOf(
            "/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/logout",
        )

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authorization = request.getHeader("Authorization")
        if (authorization != null) {
            val principal = try {
                require(request.getHeaders("Authorization").toList().size == 1)
                require(authorization.startsWith("Bearer ", ignoreCase = true) && authorization.length <= 4096)
                val jwt = tokens.decode(authorization.substring(7))
                identities.authenticated(UUID.fromString(requireNotNull(jwt.subject)), UUID.fromString(requireNotNull(jwt.getClaimAsString("sid"))))
                    ?: throw IllegalArgumentException("Inactive session")
            } catch (_: JwtException) {
                problems.write(request, response, HttpStatus.UNAUTHORIZED)
                return
            } catch (_: IllegalArgumentException) {
                problems.write(request, response, HttpStatus.UNAUTHORIZED)
                return
            } catch (_: ClassCastException) {
                problems.write(request, response, HttpStatus.UNAUTHORIZED)
                return
            } catch (_: DataAccessException) {
                problems.write(request, response, HttpStatus.INTERNAL_SERVER_ERROR)
                return
            }
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = UsernamePasswordAuthenticationToken(
                principal, null, listOf(SimpleGrantedAuthority("ROLE_" + principal.role)),
            )
            SecurityContextHolder.setContext(context)
        }
        chain.doFilter(request, response)
    }
}
