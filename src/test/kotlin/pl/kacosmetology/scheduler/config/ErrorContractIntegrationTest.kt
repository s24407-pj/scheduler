package pl.kacosmetology.scheduler.config

import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ErrorContractIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockkBean private lateinit var smsSender: SmsSender

    @MockkBean private lateinit var s3Client: S3Client

    @Test
    fun `request without session should return 401 problem detail`() {
        mockMvc.get("/api/reservations/me").andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    @Test
    fun `legacy bearer header cannot replace session authentication`() {
        mockMvc
            .get("/api/reservations/me") {
                header("Authorization", "Bearer not-a-jwt")
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `validation failure should list field errors`() {
        mockMvc
            .post("/api/auth/request-code") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = """{"phoneNumber":"abc"}"""
            }
            .andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("VALIDATION_FAILED") }
                jsonPath("$.errors.phoneNumber") { value("Nieprawidłowy format numeru telefonu") }
            }
    }

    @Test
    fun `malformed json should return MALFORMED_REQUEST`() {
        mockMvc
            .post("/api/auth/login-staff") {
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = "{"
            }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("MALFORMED_REQUEST") }
            }
    }

    @Test
    fun `missing query parameter should return INVALID_PARAMETER`() {
        mockMvc.get("/api/availability").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_PARAMETER") }
        }
    }

    @Test
    fun `query parameter of the wrong type should return INVALID_PARAMETER`() {
        mockMvc.get("/api/availability?employeeId=abc&serviceId=1&date=2030-01-01").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_PARAMETER") }
        }
    }

    @Test
    fun `unknown offering in availability query is an opaque missing resource`() {
        mockMvc.get("/api/availability?employeeId=1&serviceId=999999&date=2030-01-01").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `unmapped public path should return 404 instead of 500`() {
        mockMvc.get("/api/offerings/public/does/not/exist").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `openapi docs should be disabled outside the dev profile`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isNotFound() }
        }
    }
}
