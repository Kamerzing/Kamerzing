package com.kamerzing.payment_core.shared.web

import com.kamerzing.payment_core.support.PostgresTestConfiguration
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration::class, ApiHttpIntegrationTests.HttpTestController::class)
class ApiHttpIntegrationTests {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mapper: JsonMapper

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .proxy(ProxySelector.of(null))
        .build()

    private lateinit var accessToken: String

    @BeforeEach
    fun authenticate() {
        val credentials = mapper.writeValueAsString(mapOf(
            "email" to "http-${UUID.randomUUID()}@example.test",
            "password" to "Test-password-123!",
        ))
        fun auth(path: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(credentials))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, auth("register").statusCode())
        val login = auth("login")
        assertEquals(200, login.statusCode())
        accessToken = mapper.readTree(login.body()).path("accessToken").asString()
    }

    @Test
    fun `Boot missing route uses our problem contract and generated trace`() {
        val suppliedTrace = UUID.randomUUID().toString()
        val request = HttpRequest.newBuilder(uri("/missing-http-route"))
            .timeout(Duration.ofSeconds(10))
            .header(RequestTraceFilter.HEADER_NAME, suppliedTrace)
            .header("Authorization", "Bearer $accessToken")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertProblem(response, 404, "RESOURCE_NOT_FOUND")
        val body = mapper.readTree(response.body())
        assertEquals("/missing-http-route", body.path("instance").asString())
        assertEquals("urn:payment-core:problem:resource-not-found", body.path("type").asString())
        assertNotEquals(suppliedTrace, body.path("traceId").asString())
    }

    @Test
    fun `Boot JSON configuration rejects unknown command fields`() {
        assertProblem(post("""{"amountMinor":15000,"unexpected":"private-input"}"""), 400, "VALIDATION_ERROR")
    }

    @Test
    fun `Boot JSON configuration rejects decimal values for integer amounts`() {
        assertProblem(post("""{"amountMinor":15000.0}"""), 400, "VALIDATION_ERROR")
    }

    @Test
    fun `Boot JSON configuration accepts integer amounts`() {
        val response = post("""{"amountMinor":15000}""")
        assertEquals(200, response.statusCode())
        assertEquals(15000, mapper.readTree(response.body()).path("amountMinor").asInt())
        UUID.fromString(response.headers().firstValue(RequestTraceFilter.HEADER_NAME).orElseThrow())
    }

    @Test
    fun `Boot JSON configuration rejects quoted integer amounts`() {
        assertProblem(post("""{"amountMinor":"15000"}"""), 400, "VALIDATION_ERROR")
    }

    @Test
    fun `Boot JSON configuration rejects blank strings for integer amounts`() {
        assertProblem(post("""{"amountMinor":""}"""), 400, "VALIDATION_ERROR")
        assertProblem(post("""{"amountMinor":" "}"""), 400, "VALIDATION_ERROR")
    }

    @Test
    fun `Boot JSON configuration rejects boolean values for integer amounts`() {
        assertProblem(post("""{"amountMinor":true}"""), 400, "VALIDATION_ERROR")
        assertProblem(post("""{"amountMinor":false}"""), 400, "VALIDATION_ERROR")
    }

    private fun post(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri("/__test__/commands"))
            .header("Authorization", "Bearer $accessToken")
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun assertProblem(response: HttpResponse<String>, expectedStatus: Int, expectedCode: String) {
        assertEquals(expectedStatus, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
        val body = mapper.readTree(response.body())
        assertEquals(expectedStatus, body.path("status").asInt())
        assertEquals(expectedCode, body.path("code").asString())
        val traceId = response.headers().firstValue(RequestTraceFilter.HEADER_NAME).orElseThrow()
        UUID.fromString(traceId)
        assertEquals(traceId, body.path("traceId").asString())
    }

    private fun uri(path: String): URI = URI.create("http://127.0.0.1:$port$path")

    @TestComponent
    @RestController
    class HttpTestController {
        @PostMapping("/__test__/commands")
        fun command(@Valid @RequestBody body: Command): Command = body
    }

    data class Command(@field:Positive val amountMinor: Long)
}
