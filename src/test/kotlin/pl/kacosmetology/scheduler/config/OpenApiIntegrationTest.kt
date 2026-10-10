package pl.kacosmetology.scheduler.config

import com.ninjasquad.springmockk.MockkBean
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

@SpringBootTest(properties = ["springdoc.api-docs.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OpenApiIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockkBean private lateinit var smsSender: SmsSender

    @MockkBean private lateinit var s3Client: S3Client

    @Test
    fun `api docs should describe endpoints and the bearer security scheme`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/reservations'].post") { exists() }
            jsonPath("$.paths['/api/availability'].get") { exists() }
            jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
        }
    }
}
