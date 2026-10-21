package pl.kacosmetology.scheduler.auth.sms

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/** Verifies Twilio's wire contract and one-attempt provider classification without a real SMS. */
class TwilioSmsSenderTest {
    private val accountSid = "AC" + "a".repeat(32)
    private val keySid = "SK" + "b".repeat(32)
    private val serviceSid = "MG" + "c".repeat(32)
    private val secret = "secret-value"
    private lateinit var server: HttpServer
    private lateinit var sender: TwilioSmsSender
    private val calls = AtomicInteger()
    private var status = 201
    private var body = "{\"sid\":\"SM${"d".repeat(32)}\"}"
    private var responseDelayMillis = 0L
    private var requestPath = ""
    private var requestMethod = ""
    private var requestAuth = ""
    private var requestType = ""
    private var requestBody = ""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            requestPath = exchange.requestURI.path
            requestMethod = exchange.requestMethod
            requestAuth = exchange.requestHeaders.getFirst("Authorization")
            requestType = exchange.requestHeaders.getFirst("Content-Type")
            requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            if (responseDelayMillis > 0) Thread.sleep(responseDelayMillis)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        sender =
            TwilioSmsSender(
                accountSid,
                keySid,
                secret,
                serviceSid,
                JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:${server.address.port}"),
            )
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `OTP request authenticates and form encodes five minute Polish message`() {
        assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverOtp("+48123123123", "123456"))
        assertEquals(1, calls.get())
        assertEquals("POST", requestMethod)
        assertEquals("/2010-04-01/Accounts/$accountSid/Messages.json", requestPath)
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", requestType)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("$keySid:$secret".toByteArray(StandardCharsets.UTF_8)),
            requestAuth,
        )
        assertEquals(
            mapOf(
                "To" to "+48123123123",
                "Body" to "Kod logowania: 123456. Ważny przez 5 minut.",
                "MessagingServiceSid" to serviceSid,
            ),
            form(),
        )
        assertFalse(requestBody.contains("From="))
    }

    @Test
    fun `appointment body passes through unchanged`() {
        val message = "Wizyta: masaż & twarz + 10:30"
        assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverMessage("+48123123123", message))
        assertEquals(message, form()["Body"])
        assertEquals(1, calls.get())
    }

    @Test
    fun `provider statuses are classified with one request each`() {
        listOf(
                429 to SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE,
                400 to SmsDeliveryResult.PERMANENT_REJECTION,
                401 to SmsDeliveryResult.PERMANENT_REJECTION,
                500 to SmsDeliveryResult.AMBIGUOUS,
                503 to SmsDeliveryResult.AMBIGUOUS,
                302 to SmsDeliveryResult.AMBIGUOUS,
            )
            .forEachIndexed { index, (code, expected) ->
                status = code
                body = "provider-secret-body"
                assertEquals(expected, sender.deliverMessage("+48123123123", "private-message"))
                assertEquals(index + 1, calls.get())
            }
    }

    @Test
    fun `malformed creation response is ambiguous and never retried by adapter`() {
        body = "{\"sid\":\"unexpected\"}"
        assertEquals(SmsDeliveryResult.AMBIGUOUS, sender.deliverMessage("+48123123123", "message"))
        assertEquals(1, calls.get())
        body = "not json"
        assertEquals(SmsDeliveryResult.AMBIGUOUS, sender.deliverMessage("+48123123123", "message"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `transport failure is ambiguous`() {
        server.stop(0)
        assertEquals(SmsDeliveryResult.AMBIGUOUS, sender.deliverMessage("+48123123123", "message"))
        assertEquals(0, calls.get())
    }

    @Test
    fun `read timeout is ambiguous and does not make another request`() {
        responseDelayMillis = 250
        val shortTimeoutSender =
            TwilioSmsSender(
                accountSid,
                keySid,
                secret,
                serviceSid,
                JsonMapper.builder().build(),
                URI.create("http://127.0.0.1:${server.address.port}"),
                50,
            )
        assertEquals(SmsDeliveryResult.AMBIGUOUS, shortTimeoutSender.deliverMessage("+48123123123", "message"))
        assertEquals(1, calls.get())
    }

    @Test
    fun `provider response and credentials never enter logs`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val captured = ListAppender<ILoggingEvent>()
        captured.start()
        root.addAppender(captured)
        try {
            status = 400
            body = "provider-private-error"
            assertEquals(SmsDeliveryResult.PERMANENT_REJECTION, sender.deliverOtp("+48123123123", "123456"))
            val output = captured.list.joinToString("\n") { it.formattedMessage }
            assertFalse(output.contains(secret))
            assertFalse(output.contains(keySid))
            assertFalse(output.contains("123456"))
            assertFalse(output.contains("provider-private-error"))
        } finally {
            root.detachAppender(captured)
            captured.stop()
        }
    }

    private fun form(): Map<String, String> =
        requestBody.split("&").associate {
            val pair = it.split("=", limit = 2)
            pair[0] to URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
        }
}
