package pl.kacosmetology.scheduler.whatsapp

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WhatsAppWebhookIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var s3Client: S3Client

    @MockkBean
    private lateinit var whatsAppSender: WhatsAppSender

    @Test
    fun `GET webhook should return challenge when token matches`() {
        mockMvc.get("/api/whatsapp/webhook") {
            param("hub.mode", "subscribe")
            param("hub.verify_token", "dev_verify_token")
            param("hub.challenge", "test123")
        }.andExpect {
            status { isOk() }
            content { string("test123") }
        }
    }

    @Test
    fun `GET webhook should return 403 when token does not match`() {
        mockMvc.get("/api/whatsapp/webhook") {
            param("hub.mode", "subscribe")
            param("hub.verify_token", "wrong_token")
            param("hub.challenge", "test123")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `GET webhook should return 403 when mode is not subscribe`() {
        mockMvc.get("/api/whatsapp/webhook") {
            param("hub.mode", "unsubscribe")
            param("hub.verify_token", "dev_verify_token")
            param("hub.challenge", "test123")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST webhook with text message should invoke conversationHandler and return 200`() {
        every { whatsAppSender.sendMessage(any(), any()) } just Runs

        val payload = """
            {
              "object": "whatsapp_business_account",
              "entry": [{
                "changes": [{
                  "value": {
                    "messages": [{
                      "from": "48123456789",
                      "type": "text",
                      "text": { "body": "cześć" }
                    }]
                  }
                }]
              }]
            }
        """.trimIndent()

        mockMvc.post("/api/whatsapp/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isOk() }
        }

        verify { whatsAppSender.sendMessage(eq("+48123456789"), any()) }
    }

    @Test
    fun `POST webhook with image message should return 200 and not invoke sender`() {
        val payload = """
            {
              "object": "whatsapp_business_account",
              "entry": [{
                "changes": [{
                  "value": {
                    "messages": [{
                      "from": "48111222333",
                      "type": "image",
                      "text": null
                    }]
                  }
                }]
              }]
            }
        """.trimIndent()

        mockMvc.post("/api/whatsapp/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isOk() }
        }

        verify(exactly = 0) { whatsAppSender.sendMessage(any(), any()) }
    }

    @Test
    fun `POST webhook with empty entry list should return 200`() {
        val payload = """{ "object": "whatsapp_business_account", "entry": [] }"""

        mockMvc.post("/api/whatsapp/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isOk() }
        }
    }
}
