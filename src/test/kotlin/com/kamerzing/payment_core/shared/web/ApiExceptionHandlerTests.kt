package com.kamerzing.payment_core.shared.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.test.context.TestComponent
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

class ApiExceptionHandlerTests {

    private lateinit var mvc: MockMvc
    private lateinit var validator: LocalValidatorFactoryBean

    @BeforeEach
    fun setUp() {
        validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mvc = MockMvcBuilders.standaloneSetup(TestController())
            .setControllerAdvice(ApiExceptionHandler())
            .setValidator(validator)
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(RequestTraceFilter())
            .build()
    }

    @AfterEach
    fun tearDown() {
        validator.close()
        MDC.remove(RequestTraceFilter.MDC_KEY)
    }

    @Test
    fun `valid JSON reaches the controller`() {
        mvc.perform(
            post("/test/commands").contentType(MediaType.APPLICATION_JSON).content("""{"amountMinor":15000}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.amountMinor").value(15000))
    }

    @Test
    fun `bean validation returns a correlated problem without rejected values`() {
        val result = mvc.perform(
            post("/test/commands").contentType(MediaType.APPLICATION_JSON).content("""{"amountMinor":0}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:payment-core:problem:validation-error"))
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("The request contains invalid data."))
            .andExpect(jsonPath("$.instance").value("/test/commands"))
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andReturn()

        assertCorrelated(result)
        assertIs<MethodArgumentNotValidException>(result.resolvedException)
        assertFalse(result.response.contentAsString.contains("must be greater"))
        assertNull(MDC.get(RequestTraceFilter.MDC_KEY))
    }

    @Test
    fun `malformed JSON is a validation problem`() {
        val result = mvc.perform(
            post("/test/commands").contentType(MediaType.APPLICATION_JSON).content("{not-json"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andReturn()

        assertCorrelated(result)
        assertFalse(result.response.contentAsString.contains("not-json"))
    }

    @Test
    fun `invalid path variable uses the same validation contract`() {
        val result = mvc.perform(get("/test/ids/not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andReturn()

        assertCorrelated(result)
    }

    @Test
    fun `missing route returns a not found problem`() {
        val result = mvc.perform(get("/missing-route"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.instance").value("/missing-route"))
            .andReturn()

        assertCorrelated(result)
    }

    @Test
    fun `unsupported method preserves Allow header`() {
        val result = mvc.perform(post("/test/trace"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", "GET"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andReturn()

        assertCorrelated(result)
    }

    @Test
    fun `unsupported request media type is a problem`() {
        val result = mvc.perform(
            post("/test/commands").contentType(MediaType.TEXT_PLAIN).content("private-input"),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andReturn()

        assertCorrelated(result)
        assertFalse(result.response.contentAsString.contains("private-input"))
    }

    @Test
    fun `unexpected exception returns a safe internal error`() {
        val result = mvc.perform(get("/test/failure"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andReturn()

        assertCorrelated(result)
        assertFalse(result.response.contentAsString.contains("super-secret-database-password"))
        assertFalse(result.response.contentAsString.contains("IllegalStateException"))
        assertNull(MDC.get(RequestTraceFilter.MDC_KEY))
    }

    @Test
    fun `framework error response retains status but conceals its reason`() {
        val result = mvc.perform(get("/test/status/503"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"))
            .andReturn()

        assertCorrelated(result)
        assertFalse(result.response.contentAsString.contains("super-secret-database-password"))
    }

    @Test
    fun `client cannot choose the trace identifier and MDC is restored`() {
        val suppliedId = UUID.randomUUID().toString()
        MDC.put(RequestTraceFilter.MDC_KEY, "outer-context")
        val first = mvc.perform(get("/test/trace").header(RequestTraceFilter.HEADER_NAME, suppliedId))
            .andExpect(status().isOk)
            .andReturn()
        val generatedId = first.response.getHeader(RequestTraceFilter.HEADER_NAME)
        UUID.fromString(generatedId)
        assertNotEquals(suppliedId, generatedId)
        mvcResultTrace(first, generatedId!!)
        assertEquals("outer-context", MDC.get(RequestTraceFilter.MDC_KEY))

        val second = mvc.perform(get("/test/trace"))
            .andExpect(status().isOk)
            .andReturn()
        assertNotEquals(generatedId, second.response.getHeader(RequestTraceFilter.HEADER_NAME))
        assertEquals("outer-context", MDC.get(RequestTraceFilter.MDC_KEY))
    }

    private fun assertCorrelated(result: MvcResult) {
        val traceId = result.response.getHeader(RequestTraceFilter.HEADER_NAME)
        UUID.fromString(traceId)
        mvcResultTrace(result, traceId!!)
    }

    private fun mvcResultTrace(result: MvcResult, traceId: String) {
        jsonPath("$.traceId").value(traceId).match(result)
    }

    @TestComponent
    @RestController
    class TestController {
        @PostMapping("/test/commands")
        fun command(@Valid @RequestBody body: TestCommand): TestCommand = body

        @GetMapping("/test/ids/{id}")
        fun identifier(@PathVariable id: UUID): Map<String, UUID> = mapOf("id" to id)

        @GetMapping("/test/trace")
        fun trace(): Map<String, String?> = mapOf("traceId" to MDC.get(RequestTraceFilter.MDC_KEY))

        @GetMapping("/test/failure")
        fun failure(): Nothing = throw IllegalStateException("super-secret-database-password")

        @GetMapping("/test/status/{status}")
        fun statusFailure(@PathVariable status: Int): Nothing =
            throw ResponseStatusException(HttpStatus.valueOf(status), "super-secret-database-password")
    }

    data class TestCommand(@field:Positive val amountMinor: Long)
}
