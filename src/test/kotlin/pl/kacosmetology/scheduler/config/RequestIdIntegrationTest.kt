package pl.kacosmetology.scheduler.config

import com.ninjasquad.springmockk.MockkBean
import java.util.*
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class RequestIdIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockkBean private lateinit var smsSender: SmsSender

    @MockkBean private lateinit var s3Client: S3Client

    @Test
    fun `response should carry a generated request ID`() {
        val response = mockMvc.get("/actuator/health").andExpect { status { isOk() } }.andReturn().response

        UUID.fromString(response.getHeader(RequestIdFilter.HEADER))
    }

    @Test
    fun `response should echo a valid incoming request ID even when unauthenticated`() {
        mockMvc
            .get("/api/reservations/me") { header(RequestIdFilter.HEADER, "trace-42") }
            .andExpect {
                status { isUnauthorized() }
                header { string(RequestIdFilter.HEADER, "trace-42") }
            }
    }
}
