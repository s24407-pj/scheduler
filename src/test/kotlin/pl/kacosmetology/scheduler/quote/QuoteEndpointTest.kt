package pl.kacosmetology.scheduler.quote

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.auth.RedisRollingRateLimiter
import pl.kacosmetology.scheduler.quote.dto.QuoteResponse
import pl.kacosmetology.scheduler.reservation.BookingSubmissionService
import pl.kacosmetology.scheduler.security.CustomUserDetails

@WebMvcTest(QuoteController::class)
@Import(QuoteEndpointTest.EndpointSecurity::class)
class QuoteEndpointTest {
    @Autowired private lateinit var mvc: MockMvc
    @MockkBean private lateinit var service: QuoteService
    @MockkBean private lateinit var limits: RedisRollingRateLimiter
    @MockkBean private lateinit var submissions: BookingSubmissionService
    private val customer = CustomUserDetails(3, "+48111222333", "ROLE_CUSTOMER")
    private val body = """{"serviceId":2,"startTime":"2026-06-01T14:00:00"}"""

    @Test
    fun `customer creates quote with CSRF using authenticated account identity`() {
        every { service.create(3, any()) } returns
            QuoteResponse(
                UUID.randomUUID(),
                1,
                2,
                "Accepted",
                86,
                30,
                LocalDateTime.parse("2026-06-01T14:00:00"),
                Instant.parse("2026-06-01T08:10:00Z"),
            )
        mvc.post("/api/quotes") {
                with(user(customer))
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            .andExpect {
                status { isCreated() }
                jsonPath("$.price") { value(86) }
                jsonPath("$.quoteId") { exists() }
            }
        verify(exactly = 1) { service.create(3, any()) }
    }

    @Test
    fun `quote endpoint rejects anonymous staff and missing CSRF`() {
        mvc.post("/api/quotes") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            .andExpect { status { isUnauthorized() } }
        mvc.post("/api/quotes") {
                with(user(CustomUserDetails(3, "staff", "ROLE_OWNER", 1, 5)))
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            .andExpect { status { isForbidden() } }
        mvc.post("/api/quotes") {
                with(user(customer))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            .andExpect { status { isForbidden() } }
        verify(exactly = 0) { service.create(any(), any()) }
    }

    @Test
    fun `quote endpoint validates positive service ID`() {
        mvc.post("/api/quotes") {
                with(user(customer))
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = body.replace("\"serviceId\":2", "\"serviceId\":0")
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
                jsonPath("$.errors.serviceId") { exists() }
            }
    }

    @TestConfiguration
    @EnableMethodSecurity
    class EndpointSecurity {
        @Bean
        fun chain(http: HttpSecurity): SecurityFilterChain =
            http
                .authorizeHttpRequests { it.anyRequest().authenticated() }
                .exceptionHandling { it.authenticationEntryPoint { _, response, _ -> response.sendError(401) } }
                .build()
    }
}
