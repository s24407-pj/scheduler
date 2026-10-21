package pl.kacosmetology.scheduler.auth.sms

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import tools.jackson.databind.ObjectMapper

/** Sends SMS through Twilio's Messages API and classifies the initial provider response. */
class TwilioSmsSender(
    accountSid: String,
    apiKeySid: String,
    apiKeySecret: String,
    private val messagingServiceSid: String,
    private val mapper: ObjectMapper,
    baseUri: URI = URI.create("https://api.twilio.com"),
    private val readTimeoutMillis: Int = 10_000,
) : SmsSender {
    private val endpoint = baseUri.resolve("/2010-04-01/Accounts/$accountSid/Messages.json")
    private val authorization =
        "Basic " + Base64.getEncoder().encodeToString("$apiKeySid:$apiKeySecret".toByteArray(StandardCharsets.UTF_8))

    init {
        require(accountSid.matches(Regex("AC[a-fA-F0-9]{32}"))) { "Invalid Twilio account SID" }
        require(apiKeySid.matches(Regex("SK[a-fA-F0-9]{32}"))) { "Invalid Twilio API key SID" }
        require(apiKeySecret.isNotBlank()) { "Missing Twilio API key secret" }
        require(messagingServiceSid.matches(Regex("MG[a-fA-F0-9]{32}"))) { "Invalid Twilio Messaging Service SID" }
    }

    /** Sends a short Polish code message with its original five-minute validity. */
    override fun deliverOtp(phoneNumber: String, code: String): SmsDeliveryResult =
        deliverMessage(phoneNumber, "Kod logowania: $code. Ważny przez 5 minut.")

    /** Submits one message; only an explicit 429 is safe to retry automatically. */
    override fun deliverMessage(phoneNumber: String, message: String): SmsDeliveryResult {
        val form =
            listOf("To" to phoneNumber, "Body" to message, "MessagingServiceSid" to messagingServiceSid).joinToString(
                "&",
            ) { (key, value) ->
                "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
        val bytes = form.toByteArray(StandardCharsets.UTF_8)
        return try {
            val connection = endpoint.toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 3_000
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", authorization)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                // Fixed-length streaming prevents HttpURLConnection from replaying this POST.
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.doOutput = true
                connection.outputStream.use { it.write(bytes) }
                classify(connection)
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            SmsDeliveryResult.AMBIGUOUS
        }
    }

    private fun classify(connection: HttpURLConnection): SmsDeliveryResult =
        when (connection.responseCode) {
            429 -> SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
            in 400..499 -> SmsDeliveryResult.PERMANENT_REJECTION
            201 ->
                try {
                    val sid = connection.inputStream.use { mapper.readTree(it).get("sid")?.stringValue() }
                    if (sid != null && sid.matches(Regex("SM[a-fA-F0-9]{32}"))) SmsDeliveryResult.ACCEPTED
                    else SmsDeliveryResult.AMBIGUOUS
                } catch (_: Exception) {
                    SmsDeliveryResult.AMBIGUOUS
                }
            else -> SmsDeliveryResult.AMBIGUOUS
        }

    /** Legacy callers receive a generic failure without provider response or message details. */
    override fun sendOtp(phoneNumber: String, code: String) {
        require(deliverOtp(phoneNumber, code) == SmsDeliveryResult.ACCEPTED) { "SMS was not accepted" }
    }

    /** Legacy callers receive a generic failure without provider response or message details. */
    override fun sendMessage(phoneNumber: String, message: String) {
        require(deliverMessage(phoneNumber, message) == SmsDeliveryResult.ACCEPTED) { "SMS was not accepted" }
    }
}
