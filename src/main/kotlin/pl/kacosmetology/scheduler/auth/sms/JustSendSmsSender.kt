package pl.kacosmetology.scheduler.auth.sms

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper

/** Sends individual production SMS messages through JustSend's single-message API. */
class JustSendSmsSender(
    private val appKey: String,
    private val sender: String,
    private val mapper: ObjectMapper,
    private val endpoint: URI = URI.create("https://justsend.io/api/sender/singlemessage/send"),
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    private val registry: MeterRegistry = Metrics.globalRegistry,
) : SmsSender {
    private val log = LoggerFactory.getLogger(JustSendSmsSender::class.java)

    init {
        require(appKey.isNotBlank()) { "JUSTSEND_APP_KEY must be configured" }
        require(sender.isNotBlank()) { "JUSTSEND_SENDER must be configured" }
    }

    /** Sends an OTP through the classified provider path. */
    override fun sendOtp(phoneNumber: String, code: String) {
        require(deliverOtp(phoneNumber, code) == SmsDeliveryResult.ACCEPTED) { "SMS was not accepted" }
    }

    /** Sends an appointment message through the classified provider path. */
    override fun sendMessage(phoneNumber: String, message: String) {
        require(deliverMessage(phoneNumber, message) == SmsDeliveryResult.ACCEPTED) { "SMS was not accepted" }
    }

    /** Classifies provider acceptance without inspecting or logging response bodies. */
    override fun deliverOtp(phoneNumber: String, code: String): SmsDeliveryResult =
        deliver(phoneNumber, "Kod weryfikacyjny: $code")

    /** Classifies provider acceptance without inspecting or logging response bodies. */
    override fun deliverMessage(phoneNumber: String, message: String): SmsDeliveryResult = deliver(phoneNumber, message)

    private fun deliver(phoneNumber: String, content: String): SmsDeliveryResult {
        val attemptId = UUID.randomUUID()
        val length = SmsSegmentCounter.count(content)
        if (length.segments > 1) {
            log.atWarn()
                .addKeyValue("attemptId", attemptId)
                .addKeyValue("encoding", length.encoding)
                .addKeyValue("encodedUnits", length.units)
                .addKeyValue("segments", length.segments)
                .log("Multipart SMS may incur additional charges")
        }
        var httpStatus: Int? = null
        var exceptionType: String? = null
        val started = System.nanoTime()
        val result =
            try {
                val payload =
                    mapper.writeValueAsString(
                        mapOf(
                            "sender" to sender,
                            "msisdn" to phoneNumber.removePrefix("+"),
                            "bulkVariant" to "ECO",
                            "content" to content,
                        ),
                    )
                val request =
                    HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(10))
                        .header("App-Key", appKey)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build()
                httpStatus = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
                when (httpStatus) {
                    in 200..299 -> SmsDeliveryResult.ACCEPTED
                    429 -> SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
                    else -> SmsDeliveryResult.AMBIGUOUS
                }
            } catch (exception: InterruptedException) {
                exceptionType = exception.javaClass.name
                Thread.currentThread().interrupt()
                SmsDeliveryResult.AMBIGUOUS
            } catch (exception: Exception) {
                exceptionType = exception.javaClass.name
                SmsDeliveryResult.AMBIGUOUS
            }
        val durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis()
        // Count attempted segments, including failed/ambiguous attempts; this is not a billing receipt or a cap.
        registry
            .counter("scheduler.sms.segments.attempted", "encoding", length.encoding.name, "result", result.name)
            .increment(length.segments.toDouble())
        val event = if (result == SmsDeliveryResult.ACCEPTED) log.atInfo() else log.atWarn()
        event
            .addKeyValue("attemptId", attemptId)
            .addKeyValue("httpStatus", httpStatus)
            .addKeyValue("exceptionType", exceptionType)
            .addKeyValue("durationMs", durationMs)
            .addKeyValue("result", result)
            .addKeyValue("encoding", length.encoding)
            .addKeyValue("segments", length.segments)
            .log("JustSend SMS attempt completed")
        return result
    }
}

/** Registers JustSend only for production and validates its required operator credentials. */
@Configuration
@Profile("prod")
class JustSendSmsConfig {
    /** Creates the production sender. */
    @Bean
    fun justSendSmsSender(
        @Value($$"${justsend.app-key:}") appKey: String,
        @Value($$"${justsend.sender:}") sender: String,
        mapper: ObjectMapper,
        registry: MeterRegistry,
    ): SmsSender = JustSendSmsSender(appKey, sender, mapper, registry = registry)
}
