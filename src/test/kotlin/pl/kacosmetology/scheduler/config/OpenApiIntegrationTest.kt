package pl.kacosmetology.scheduler.config

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import software.amazon.awssdk.services.s3.S3Client
import tools.jackson.databind.ObjectMapper

@SpringBootTest(properties = ["springdoc.api-docs.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OpenApiIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper

    @MockkBean private lateinit var smsSender: SmsSender

    @MockkBean private lateinit var s3Client: S3Client

    @Test
    fun `api docs should describe endpoints and the session cookie security scheme`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/reservations'].post") { exists() }
            jsonPath("$.paths['/api/availability'].get") { exists() }
            jsonPath("$.components.securitySchemes.sessionAuth.type") { value("apiKey") }
            jsonPath("$.components.securitySchemes.sessionAuth.in") { value("cookie") }
            jsonPath("$.components.securitySchemes.sessionAuth.name") { value("SESSION") }
            jsonPath("$.components.securitySchemes.csrfToken.name") { value("X-XSRF-TOKEN") }
            jsonPath("$.paths['/api/availability'].get.security.length()") { value(0) }
            jsonPath("$.paths['/api/reservations'].post.responses['409'].content['application/problem+json']") {
                exists()
            }
            jsonPath("$.components.schemas.ApiProblemDetail.properties.code.type") { value("string") }
            jsonPath("$.paths['/api/reservations'].post.security[0].sessionAuth") { exists() }
            jsonPath("$.paths['/api/reservations'].post.security[0].csrfToken") { exists() }
            jsonPath("$.paths['/api/auth/verify-code'].post.security[0].sessionAuth") { doesNotExist() }
            jsonPath("$.paths['/api/auth/verify-code'].post.security[0].csrfToken") { exists() }
            jsonPath("$.paths['/api/auth/staff-password'].post.security[0].sessionAuth") { doesNotExist() }
            jsonPath("$.paths['/api/auth/staff-password'].post.security[0].csrfToken") { exists() }
            jsonPath("$.paths['/api/staff/password'].post.security[0].sessionAuth") { exists() }
            jsonPath("$.paths['/api/staff/password'].post.security[0].csrfToken") { exists() }
            jsonPath("$.paths['/api/customers/{id}/visits'].get") { exists() }
            jsonPath("$.paths['/api/users/me'].put") { doesNotExist() }
            jsonPath("$.components.schemas.StaffLoginRequest.properties.companyId") { exists() }
            jsonPath("$.components.schemas.StaffLoginRequest.properties.employmentId") { doesNotExist() }
        }
    }

    @Test
    fun `generated contract requires booking keys revisions and exact lifecycle with local times`() {
        val response = mockMvc.get("/v3/api-docs").andReturn().response
        assertEquals(200, response.status)
        val api = mapper.readTree(response.contentAsString)
        for (path in listOf("/api/reservations", "/api/reservations/staff")) {
            val header =
                api["paths"][path]["post"]["parameters"].values().single { it["name"].asString() == "Idempotency-Key" }
            assertTrue(header["required"].asBoolean())
        }
        for ((path, method) in
            listOf(
                "/api/reservations/{id}" to "put",
                "/api/reservations/{id}/cancel" to "patch",
                "/api/reservations/{id}/restore" to "patch",
                "/api/reservations/{id}/archive" to "patch",
            )) {
            val header =
                api["paths"][path][method]["parameters"].values().single { it["name"].asString() == "If-Match" }
            assertTrue(header["required"].asBoolean())
        }
        assertNull(api["paths"]["/api/reservations/{id}"]["delete"])
        assertEquals(
            setOf("BOOKED", "CANCELLED", "COMPLETED", "NO_SHOW"),
            api["components"]["schemas"]["ReservationStatus"]["enum"].values().map { it.asString() }.toSet(),
        )
        val time = api["components"]["schemas"]["CreateReservationRequest"]["properties"]["startTime"]
        assertEquals("local-date-time", time["format"].asString())
        assertNotNull(time["pattern"])
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/generated"))
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/generated/openapi.json"), response.contentAsString)
    }
}
