package pl.kacosmetology.scheduler.auth.sms

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.Supplier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/** Contract tests for production SMS requests and classification, with no external provider calls. */
class JustSendSmsSenderTest {
    private val mapper = JsonMapper.builder().build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val received = LinkedBlockingQueue<Pair<com.sun.net.httpserver.Headers, String>>()
    private var status = 200

    init {
        server.createContext("/api/sender/singlemessage/send") { exchange ->
            received.add(
                exchange.requestHeaders to exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach fun stopServer() = server.stop(0)

    private fun sender(): JustSendSmsSender =
        JustSendSmsSender(
            "secret-key",
            "JUSTSEND",
            mapper,
            URI.create("http://127.0.0.1:${server.address.port}/api/sender/singlemessage/send"),
        )

    @Test
    fun `OTP request has exact fields and provider headers`() {
        assertEquals(SmsDeliveryResult.ACCEPTED, sender().deliverOtp("+48500111222", "123456"))
        val (headers, body) = received.take()
        assertEquals("secret-key", headers.getFirst("App-Key"))
        assertEquals("application/json", headers.getFirst("Accept"))
        assertEquals("application/json", headers.getFirst("Content-Type"))
        val fields = mapper.readTree(body)
        assertEquals(setOf("sender", "msisdn", "bulkVariant", "content"), fields.properties().map { it.key }.toSet())
        assertEquals("JUSTSEND", fields["sender"].asString())
        assertEquals("48500111222", fields["msisdn"].asString())
        assertEquals("ECO", fields["bulkVariant"].asString())
        assertEquals("Kod weryfikacyjny: 123456", fields["content"].asString())
    }

    @Test
    fun `appointment text including Polish characters is unchanged`() {
        val message = "Wizyta: środa, ąęłńóśźż."
        assertEquals(SmsDeliveryResult.ACCEPTED, sender().deliverMessage("+48500111222", message))
        assertEquals(message, mapper.readTree(received.take().second)["content"].asString())
    }

    @Test
    fun `HTTP statuses have conservative classifications`() {
        for ((httpStatus, expected) in
            listOf(
                202 to SmsDeliveryResult.ACCEPTED,
                429 to SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE,
                400 to SmsDeliveryResult.AMBIGUOUS,
                500 to SmsDeliveryResult.AMBIGUOUS,
            )) {
            status = httpStatus
            assertEquals(expected, sender().deliverMessage("+48500111222", "Wizyta"))
            received.take()
        }
    }

    @Test
    fun `connection failure is ambiguous`() {
        val uri = URI.create("http://127.0.0.1:1/api/sender/singlemessage/send")
        assertEquals(
            SmsDeliveryResult.AMBIGUOUS,
            JustSendSmsSender("key", "sender", mapper, uri).deliverOtp("+48500111222", "123456"),
        )
    }

    @Test
    fun `production configuration selects sender and rejects blank credentials`() {
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(JustSendSmsConfig::class.java)
                .withBean(ObjectMapper::class.java, Supplier { mapper })
                .withPropertyValues("spring.profiles.active=prod", "justsend.app-key=key", "justsend.sender=JUSTSEND")
        runner.run { context ->
            assertFalse(context.startupFailure != null)
            assertTrue(context.getBean(SmsSender::class.java) is JustSendSmsSender)
        }
        runner.withPropertyValues("justsend.app-key= ").run { context ->
            assertTrue(context.startupFailure != null)
        }
        runner.withPropertyValues("justsend.sender= ").run { context ->
            assertTrue(context.startupFailure != null)
        }
        ApplicationContextRunner()
            .withUserConfiguration(JustSendSmsConfig::class.java)
            .withPropertyValues("spring.profiles.active=dev")
            .run { context -> assertFalse(context.containsBean("justSendSmsSender")) }
    }
}
