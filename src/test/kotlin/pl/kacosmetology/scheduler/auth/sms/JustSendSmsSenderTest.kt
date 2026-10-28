package pl.kacosmetology.scheduler.auth.sms

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

/** Contract tests for production SMS requests and classification, with no external provider calls. */
class JustSendSmsSenderTest {
    private val mapper = JsonMapper.builder().build()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val received = LinkedBlockingQueue<Pair<com.sun.net.httpserver.Headers, String>>()
    private val registry = SimpleMeterRegistry()
    private val logger = LoggerFactory.getLogger(JustSendSmsSender::class.java) as Logger
    private val logs = ListAppender<ILoggingEvent>()
    private var status = 200

    init {
        logs.start()
        logger.addAppender(logs)
        server.createContext("/api/sender/singlemessage/send") { exchange ->
            received.add(
                exchange.requestHeaders to exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8),
            )
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
        logger.detachAppender(logs)
        logs.stop()
        registry.close()
    }

    private fun sender(): JustSendSmsSender =
        JustSendSmsSender(
            "secret-key",
            "JUSTSEND",
            mapper,
            URI.create("http://127.0.0.1:${server.address.port}/api/sender/singlemessage/send"),
            registry = registry,
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
    fun `attempt logs contain safe metadata and segment counter tracks every outcome`() {
        val message = "ą".repeat(71)
        for (code in listOf(200, 429, 500)) {
            status = code
            sender().deliverMessage("+48500111222", message)
            received.take()
        }
        val completions = logs.list.filter { it.message == "JustSend SMS attempt completed" }
        assertEquals(3, completions.size)
        val ids = completions.map { event ->
            val fields = event.keyValuePairs.associate { it.key to it.value }
            assertEquals(
                setOf("attemptId", "httpStatus", "exceptionType", "durationMs", "result", "encoding", "segments"),
                fields.keys,
            )
            assertTrue((fields["durationMs"] as Long) >= 0)
            assertEquals(null, fields["exceptionType"])
            assertEquals(2, fields["segments"])
            UUID.fromString(fields["attemptId"].toString())
        }
        assertEquals(3, ids.toSet().size)
        assertEquals(
            listOf(200, 429, 500),
            completions.map { e -> e.keyValuePairs.single { it.key == "httpStatus" }.value },
        )
        val warnings = logs.list.filter { it.message == "Multipart SMS may incur additional charges" }
        assertEquals(3, warnings.size)
        assertTrue(warnings.all { it.level == Level.WARN })
        assertEquals(ids.toSet(), warnings.map { e -> e.keyValuePairs.single { it.key == "attemptId" }.value }.toSet())
        assertEquals(6.0, registry.find("scheduler.sms.segments.attempted").counters().sumOf { it.count() })
        for (result in SmsDeliveryResult.entries) {
            assertEquals(
                2.0,
                registry
                    .get("scheduler.sms.segments.attempted")
                    .tags("encoding", "UCS2", "result", result.name)
                    .counter()
                    .count(),
            )
        }
        assertSafeLogs(message)
    }

    @Test
    fun `OTP log never exposes phone code key or body and single segment has no warning`() {
        sender().deliverOtp("+48500111222", "123456")
        assertEquals(1, logs.list.size)
        assertEquals(Level.INFO, logs.list.single().level)
        assertEquals(
            1.0,
            registry
                .get("scheduler.sms.segments.attempted")
                .tags("encoding", "GSM7", "result", "ACCEPTED")
                .counter()
                .count(),
        )
        assertSafeLogs("Kod weryfikacyjny: 123456")
    }

    @Test
    fun `transport errors log only exception type and interrupted status is restored`() {
        val client = mockk<HttpClient>()
        for (failure in
            listOf(HttpTimeoutException("secret-key 123456 SMS body"), InterruptedException("secret-key 123456"))) {
            logs.list.clear()
            every { client.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<Void>>()) } throws failure
            try {
                assertEquals(
                    SmsDeliveryResult.AMBIGUOUS,
                    JustSendSmsSender("secret-key", "JUSTSEND", mapper, client = client, registry = registry)
                        .deliverOtp("+48500111222", "123456"),
                )
                val event = logs.list.single()
                val fields = event.keyValuePairs.associate { it.key to it.value }
                assertEquals(failure.javaClass.name, fields["exceptionType"])
                assertEquals(null, fields["httpStatus"])
                assertEquals(Level.WARN, event.level)
                assertEquals(failure is InterruptedException, Thread.currentThread().isInterrupted)
                assertSafeLogs("SMS body")
            } finally {
                Thread.interrupted()
            }
        }
        assertEquals(
            2.0,
            registry
                .get("scheduler.sms.segments.attempted")
                .tags("encoding", "GSM7", "result", "AMBIGUOUS")
                .counter()
                .count(),
        )
    }

    private fun assertSafeLogs(content: String) {
        logs.list.forEach { event ->
            val rendered = "${event.formattedMessage} ${event.keyValuePairs} ${event.argumentArray?.toList()}"
            for (secret in listOf("secret-key", "123456", "48500111222", content)) assertFalse(
                rendered.contains(secret),
            )
            assertEquals(null, event.throwableProxy)
        }
    }

    @Test
    fun `production configuration selects sender and rejects blank credentials`() {
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(JustSendSmsConfig::class.java)
                .withBean(ObjectMapper::class.java, { mapper })
                .withBean(MeterRegistry::class.java, { registry })
                .withPropertyValues("spring.profiles.active=prod", "justsend.app-key=key", "justsend.sender=JUSTSEND")
        runner.run { context ->
            assertFalse(context.startupFailure != null)
            assertTrue(context.getBean<SmsSender>() is JustSendSmsSender)
        }
        runner.withPropertyValues("justsend.app-key= ").run { context ->
            assertTrue(context.startupFailure != null)
        }
        runner.withPropertyValues("justsend.sender= ").run { context ->
            assertTrue(context.startupFailure != null)
        }
        runner.withPropertyValues("justsend.sender=ABCDEFGHIJK").run { context ->
            assertNull(context.startupFailure)
        }
        for (sender in listOf("ABCDEFGHIJKL", "ABCDEFGHIJKLMN")) {
            runner.withPropertyValues("justsend.sender=$sender").run { context ->
                val failure = requireNotNull(context.startupFailure)
                assertEquals(
                    "JUSTSEND_SENDER must contain at most 11 characters",
                    generateSequence(failure) { it.cause }.last().message,
                )
            }
        }
        ApplicationContextRunner()
            .withUserConfiguration(JustSendSmsConfig::class.java)
            .withPropertyValues("spring.profiles.active=dev")
            .run { context -> assertFalse(context.containsBean("justSendSmsSender")) }
    }
}
