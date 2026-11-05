package pl.kacosmetology.scheduler.security

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import pl.kacosmetology.scheduler.auth.RateLimitDecision
import pl.kacosmetology.scheduler.auth.RateLimitExceededException
import pl.kacosmetology.scheduler.auth.RedisRollingRateLimiter
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.reservation.BookingRateLimitFilter
import pl.kacosmetology.scheduler.reservation.BookingSubmissionService
import pl.kacosmetology.scheduler.reservation.MAX_BOOKING_BODY_BYTES
import tools.jackson.databind.json.JsonMapper

/** Real HTTP decoding, firewall and MVC routing with the production filters; no database or SMS is used. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiPathFilterHttpTest {
    private val principal =
        CustomUserDetails(UUID.randomUUID(), "test@example.invalid", "ROLE_OWNER", UUID.randomUUID(), UUID.randomUUID())
    private val limits = mockk<RedisRollingRateLimiter>()
    private val submissions = mockk<BookingSubmissionService>()
    private val client = HttpClient.newHttpClient()
    private val contexts = listOf("", "/scheduler")
    private lateinit var tomcat: Tomcat
    private lateinit var directory: Path
    private lateinit var baseUrl: String

    @BeforeAll
    fun startServer() {
        directory = Files.createTempDirectory("scheduler-path-filters-")
        tomcat = Tomcat()
        tomcat.setBaseDir(directory.toString())
        tomcat.setPort(0)
        tomcat.connector.setProperty("address", "127.0.0.1")
        val errors = HandlerExceptionResolver { _, response, _, failure ->
            when (failure) {
                is ApiException -> {
                    response.status = failure.code.status.value()
                    response.writer.write(failure.code.name)
                }
                is RateLimitExceededException -> {
                    response.status = 429
                    response.writer.write("RATE_LIMITED")
                }
                else -> throw AssertionError("Unexpected filter failure", failure)
            }
            ModelAndView()
        }
        for ((index, contextPath) in contexts.withIndex()) {
            val context =
                tomcat.addContext(contextPath, Files.createDirectory(directory.resolve("web-$index")).toString())
            context.parentClassLoader = javaClass.classLoader
            val mvc = AnnotationConfigWebApplicationContext()
            mvc.environment.setActiveProfiles("api-path-filter-http-test")
            mvc.register(HttpConfiguration::class.java)
            Tomcat.addServlet(context, "mvc", DispatcherServlet(mvc)).loadOnStartup = 1
            context.addServletMapping("/", "mvc")
            val firewall = StrictHttpFirewall()
            val expectedContext = ExpectedSessionContextFilter(errors)
            val booking = BookingRateLimitFilter(limits, submissions, JsonMapper.builder().build(), errors)
            context.addFilterDef(
                FilterDef().apply {
                    filterName = "production-path-filters"
                    setFilter { request, response, chain ->
                        val safeRequest = firewall.getFirewalledRequest(request as HttpServletRequest)
                        SecurityContextHolder.getContext().authentication =
                            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
                        try {
                            expectedContext.doFilter(safeRequest, response) { scoped, result ->
                                booking.doFilter(scoped, result, chain)
                            }
                        } finally {
                            SecurityContextHolder.clearContext()
                            safeRequest.reset()
                        }
                    }
                },
            )
            context.addFilterMap(
                FilterMap().apply {
                    filterName = "production-path-filters"
                    addURLPattern("/*")
                },
            )
        }
        tomcat.start()
        baseUrl = "http://127.0.0.1:${tomcat.connector.localPort}"
    }

    @BeforeEach
    fun resetDependencies() {
        clearMocks(limits, submissions)
        every { limits.check(any(), any(), any()) } returns RateLimitDecision(true, 0)
        every { submissions.bindPayload(any(), any(), any()) } just Runs
    }

    @AfterAll
    fun stopServer() {
        try {
            tomcat.stop()
            tomcat.destroy()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/company/profile", "/%61pi/company/profile", "/api/company/%70rofile"])
    fun `protected writes reject missing and stale scope but accept current scope`(path: String) {
        for (context in contexts) {
            val missing = send(context + path, "PUT", scope = null)
            assertEquals(400, missing.statusCode(), context + path)
            assertEquals("SESSION_CONTEXT_REQUIRED", missing.body())
            val stale =
                send(
                    context + path,
                    "PUT",
                    scope = scope.replace(principal.companyId.toString(), UUID.randomUUID().toString()),
                )
            assertEquals(409, stale.statusCode(), context + path)
            assertEquals("SESSION_CONTEXT_CHANGED", stale.body())
            assertEquals(200, send(context + path, "PUT").statusCode(), context + path)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/auth/logout-all", "/%61pi/auth/logout-all", "/api/auth/%6cogout-all"])
    fun `logout all remains a protected auth write`(path: String) {
        for (context in contexts) {
            val result = send(context + path, scope = null)
            assertEquals(400, result.statusCode(), context + path)
            assertEquals("SESSION_CONTEXT_REQUIRED", result.body())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/auth/totp/verify", "/%61pi/auth/totp/verify", "/api/%61uth/totp/verify"])
    fun `public auth writes keep their scope exemption`(path: String) {
        for (context in contexts) assertEquals(200, send(context + path, scope = null).statusCode(), context + path)
        verify(exactly = 0) { limits.check(any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "/api/reservations",
                "/%61pi/reservations",
                "/api/%72eservations",
                "/api/reservations/staff",
                "/api/reservations/%73taff",
                "/%61pi/%72eservations/staff",
            ],
    )
    fun `oversized bookings never reach MVC or permanent binding`(path: String) {
        for (context in contexts) {
            val result = send(context + path, body = "x".repeat(MAX_BOOKING_BODY_BYTES + 1))
            assertEquals(413, result.statusCode(), context + path)
            assertEquals("PAYLOAD_TOO_LARGE", result.body())
        }
        verify(exactly = 0) { submissions.bindPayload(any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "/api/reservations",
                "/%61pi/reservations",
                "/api/%72eservations",
                "/api/reservations/staff",
                "/api/reservations/%73taff",
                "/%61pi/%72eservations/staff",
            ],
    )
    fun `exhausted booking allowance prevents binding and dispatch`(path: String) {
        every { limits.check(any(), any(), any()) } returns RateLimitDecision(false, 60)
        for (context in contexts) {
            val result = send(context + path)
            assertEquals(429, result.statusCode(), context + path)
            assertEquals("RATE_LIMITED", result.body())
        }
        verify(exactly = 0) { submissions.bindPayload(any(), any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "/api/reservations",
                "/%61pi/reservations",
                "/api/%72eservations",
                "/api/reservations/staff",
                "/api/reservations/%73taff",
                "/%61pi/%72eservations/staff",
            ],
    )
    fun `admitted malformed booking binds its key before MVC rejects it`(path: String) {
        for (context in contexts) assertEquals(
            400,
            send(context + path, body = "{invalid").statusCode(),
            context + path,
        )
        verify(exactly = contexts.size) {
            submissions.bindPayload(principal.accountKey(), "path-test", match { it.startsWith("!invalid-json:") })
        }
    }

    private val scope: String
        get() = ExpectedSessionContextFilter.context(principal)

    private fun send(
        path: String,
        method: String = "POST",
        body: String = "{}",
        scope: String? = this.scope,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "path-test")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
        scope?.let { request.header(ExpectedSessionContextFilter.HEADER, it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    class HttpConfiguration {
        @Bean fun pathProbe() = PathProbe()
    }

    @RestController
    @Profile("api-path-filter-http-test")
    class PathProbe {
        @PutMapping("/api/company/profile") fun profile() = "reached"

        @PostMapping("/api/reservations", "/api/reservations/staff")
        fun booking(@RequestBody body: Map<String, Any?>) = body

        @PostMapping("/api/auth/totp/verify", "/api/auth/logout-all") fun auth() = "reached"
    }
}
