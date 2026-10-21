package pl.kacosmetology.scheduler.auth.sms

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
) : SmsSender {
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

    private fun deliver(phoneNumber: String, content: String): SmsDeliveryResult =
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
            when (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()) {
                in 200..299 -> SmsDeliveryResult.ACCEPTED
                429 -> SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
                else -> SmsDeliveryResult.AMBIGUOUS
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            SmsDeliveryResult.AMBIGUOUS
        } catch (_: Exception) {
            SmsDeliveryResult.AMBIGUOUS
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
    ): SmsSender = JustSendSmsSender(appKey, sender, mapper)
}
