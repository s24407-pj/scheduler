package pl.kacosmetology.scheduler.auth.sms

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import com.twilio.constant.EnumConstants.ContentType
import com.twilio.http.HttpClient
import com.twilio.http.HttpMethod
import com.twilio.http.Request
import com.twilio.http.Response
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** Verifies the SDK request, safe diagnostics and one-attempt provider classification without sending a real SMS. */
class TwilioSmsSenderTest {
    private val accountSid = "AC" + "a".repeat(32)
    private val keySid = "SK" + "b".repeat(32)
    private val serviceSid = "MG" + "c".repeat(32)
    private val secret = "secret-value"
    private lateinit var transport: RecordingTransport
    private lateinit var sender: TwilioSmsSender

    @BeforeEach
    fun start() {
        transport = RecordingTransport()
        sender = TwilioSmsSender(accountSid, keySid, secret, serviceSid, transport)
    }

    @Test
    fun `OTP request uses SDK API key authentication and Messaging Service`() {
        assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverOtp("+48123123123", "123456"))
        assertEquals(1, transport.calls)
        val request = requireNotNull(transport.request)
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("https://api.twilio.com/2010-04-01/Accounts/$accountSid/Messages.json", request.url)
        assertEquals(ContentType.FORM_URLENCODED, request.contentType)
        assertEquals(keySid, request.username)
        assertEquals(secret, request.password)
        assertEquals(
            mapOf(
                "To" to listOf("+48123123123"),
                "Body" to listOf("Kod logowania: 123456. Ważny przez 5 minut."),
                "MessagingServiceSid" to listOf(serviceSid),
            ),
            request.postParams,
        )
        assertFalse(request.postParams.containsKey("From"))
    }

    @Test
    fun `account Auth Token uses Account SID for SDK authentication`() {
        val tokenSender = TwilioSmsSender(accountSid, accountSid, secret, serviceSid, transport)

        assertEquals(
            SmsDeliveryResult.ACCEPTED,
            tokenSender.deliverMessage("+18777804236", "sms_appointment_reminders"),
        )
        assertEquals(accountSid, transport.request?.username)
        assertEquals(secret, transport.request?.password)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `configuration prefers Auth Token when both credential types are supplied`() {
        val configured =
            TwilioSmsConfiguration().twilioSmsSender(accountSid, "invalid-key", "invalid-secret", secret, serviceSid)

        assertTrue(configured is TwilioSmsSender)
    }

    @Test
    fun `appointment body passes through unchanged`() {
        val message = "Wizyta: masaż & twarz + 10:30"
        assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverMessage("+48123123123", message))
        assertEquals(listOf(message), transport.request?.postParams?.get("Body"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `provider statuses are classified with one request each even for server errors`() {
        listOf(
                429 to SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE,
                400 to SmsDeliveryResult.PERMANENT_REJECTION,
                401 to SmsDeliveryResult.PERMANENT_REJECTION,
                500 to SmsDeliveryResult.AMBIGUOUS,
                503 to SmsDeliveryResult.AMBIGUOUS,
                302 to SmsDeliveryResult.AMBIGUOUS,
            )
            .forEachIndexed { index, (code, expected) ->
                transport.status = code
                transport.body = "provider-secret-body"
                assertEquals(expected, sender.deliverMessage("+48123123123", "private-message"))
                assertEquals(index + 1, transport.calls)
            }
    }

    @Test
    fun `malformed creation response is ambiguous and never retried`() {
        transport.body = "{\"sid\":\"unexpected\"}"
        assertEquals(SmsDeliveryResult.AMBIGUOUS, sender.deliverMessage("+48123123123", "message"))
        assertEquals(1, transport.calls)
        transport.body = "not json"
        assertEquals(SmsDeliveryResult.AMBIGUOUS, sender.deliverMessage("+48123123123", "message"))
        assertEquals(2, transport.calls)
    }

    @Test
    fun `transport failure is ambiguous without another attempt`() {
        transport.fail = true
        assertEquals(SmsDeliveryResult.AMBIGUOUS, sender.deliverMessage("+48123123123", "message"))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `production SDK transport does not retry server errors`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readAllBytes()
            val body = "{\"code\":20500,\"message\":\"private-error\",\"status\":503}"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(503, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val liveTransportSender =
                TwilioSmsSender(
                    accountSid,
                    keySid,
                    secret,
                    serviceSid,
                    baseUri = URI.create("http://127.0.0.1:${server.address.port}"),
                )
            assertEquals(SmsDeliveryResult.AMBIGUOUS, liveTransportSender.deliverMessage("+48123123123", "message"))
            assertEquals(1, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider code is logged without response body or credentials`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val captured = ListAppender<ILoggingEvent>()
        captured.start()
        root.addAppender(captured)
        try {
            transport.status = 401
            transport.body = "{\"code\":70051,\"message\":\"provider-private-error\",\"status\":401}"
            assertEquals(SmsDeliveryResult.PERMANENT_REJECTION, sender.deliverOtp("+48123123123", "123456"))
            val output = captured.list.joinToString("\n") { it.formattedMessage }
            assertTrue(output.contains("httpStatus=401 providerCode=70051"))
            assertFalse(output.contains(secret))
            assertFalse(output.contains(keySid))
            assertFalse(output.contains("123456"))
            assertFalse(output.contains("provider-private-error"))
        } finally {
            root.detachAppender(captured)
            captured.stop()
        }
    }

    private class RecordingTransport : HttpClient() {
        var calls = 0
        var request: Request? = null
        var status = 201
        var body = "{\"sid\":\"SM${"d".repeat(32)}\"}"
        var fail = false

        override fun makeRequest(request: Request): Response {
            calls++
            this.request = request
            if (fail) throw IllegalStateException("transport failed")
            return Response(body, status)
        }
    }
}
