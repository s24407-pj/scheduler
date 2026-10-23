package pl.kacosmetology.scheduler.notification

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.kacosmetology.scheduler.auth.sms.JustSendSmsSender
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsEncoding
import pl.kacosmetology.scheduler.auth.sms.SmsLength
import pl.kacosmetology.scheduler.auth.sms.SmsSegmentCounter
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.testId
import tools.jackson.databind.json.JsonMapper

/** Exercises real notification templates through the HTTP adapter against a local provider stub, without paid sends. */
class NotificationSmsIntegrationTest {
    private val details = NotificationDetails(mockk())
    private val start = Instant.parse("2027-06-02T07:00:00Z")
    private val reservation =
        NotificationReservation(
            testId(1),
            testId(2),
            testId(3),
            0,
            start,
            start.plusSeconds(1800),
            start.atZone(BUSINESS_ZONE).toLocalDate(),
            "BOOKED",
            false,
            "+48500111222",
            "Strzyżenie damskie",
            "Anna Żak",
            "KA Cosmetology",
        )

    @Test
    fun `real appointment templates preserve content and count two segments with representative Polish names`() {
        val messages =
            listOf("CANCEL" to 74, "REMINDER" to 87, "EDIT" to 81, "RESTORE" to 86, "CREATE" to 88).map { (kind, units)
                ->
                details.message(kind, reservation) to units
            } + (details.summary(listOf(reservation, reservation.copy(id = testId(4)))) to 105)
        val mapper = JsonMapper.builder().build()
        val received = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val registry = SimpleMeterRegistry()
        server.createContext("/send") { exchange ->
            received.add(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val sender =
                JustSendSmsSender(
                    "test-key",
                    "JUSTSEND",
                    mapper,
                    URI.create("http://127.0.0.1:${server.address.port}/send"),
                    registry = registry,
                )
            for ((message, units) in messages) {
                assertEquals(SmsLength(SmsEncoding.UCS2, units, 2), SmsSegmentCounter.count(message))
                assertEquals(SmsDeliveryResult.ACCEPTED, sender.deliverMessage(reservation.phone, message))
                assertEquals(message, mapper.readTree(received.take())["content"].asString())
            }
            assertEquals(
                12.0,
                registry
                    .get("scheduler.sms.segments.attempted")
                    .tags("encoding", "UCS2", "result", "ACCEPTED")
                    .counter()
                    .count(),
            )
        } finally {
            server.stop(0)
            registry.close()
        }
    }

    @Test
    fun `long permitted names and cancellation summaries expose growing segment costs`() {
        val longNames =
            reservation.copy(
                serviceName = "ą".repeat(100),
                employeeName = "ą".repeat(100) + " " + "ą".repeat(100),
                companyName = "ą".repeat(255),
            )
        assertEquals(
            SmsLength(SmsEncoding.UCS2, 604, 10),
            SmsSegmentCounter.count(details.message("CREATE", longNames)),
        )
        val summary = details.summary(List(20) { reservation.copy(id = testId(it.toLong() + 10)) })
        assertEquals(SmsLength(SmsEncoding.UCS2, 681, 11), SmsSegmentCounter.count(summary))
    }
}
