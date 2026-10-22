package pl.kacosmetology.scheduler.auth.sms

import com.twilio.constant.EnumConstants.ContentType
import com.twilio.http.HttpClient
import com.twilio.http.HttpMethod
import com.twilio.http.NetworkHttpClient
import com.twilio.http.Request
import com.twilio.http.Response
import com.twilio.http.TwilioRestClient
import com.twilio.rest.api.v2010.account.Message
import java.net.URI
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.util.Timeout
import org.slf4j.LoggerFactory

/** Sends SMS with Twilio's Java SDK while preserving one provider request per durable attempt. */
class TwilioSmsSender(
    accountSid: String,
    private val credentialSid: String,
    private val credentialSecret: String,
    private val messagingServiceSid: String,
    transport: HttpClient = productionTransport(),
    private val baseUri: URI = URI.create("https://api.twilio.com"),
) : SmsSender {
    private val log = LoggerFactory.getLogger(TwilioSmsSender::class.java)
    private val endpoint = "/2010-04-01/Accounts/$accountSid/Messages.json"
    private val client =
        TwilioRestClient.Builder(credentialSid, credentialSecret).accountSid(accountSid).httpClient(transport).build()

    init {
        require(accountSid.matches(Regex("AC[a-fA-F0-9]{32}"))) { "Invalid Twilio account SID" }
        require(credentialSid == accountSid || credentialSid.matches(Regex("SK[a-fA-F0-9]{32}"))) {
            "Invalid Twilio credential SID"
        }
        require(credentialSecret.isNotBlank()) { "Missing Twilio credential secret" }
        require(messagingServiceSid.matches(Regex("MG[a-fA-F0-9]{32}"))) { "Invalid Twilio Messaging Service SID" }
    }

    /** Sends a short Polish code message with its original five-minute validity. */
    override fun deliverOtp(phoneNumber: String, code: String): SmsDeliveryResult =
        deliverMessage(phoneNumber, "Kod logowania: $code. Ważny przez 5 minut.")

    /** Submits one message; only an explicit 429 is safe to retry automatically. */
    override fun deliverMessage(phoneNumber: String, message: String): SmsDeliveryResult =
        try {
            val request = Request(HttpMethod.POST, baseUri.resolve(endpoint).toString())
            request.setContentType(ContentType.FORM_URLENCODED)
            request.addPostParam("To", phoneNumber)
            request.addPostParam("Body", message)
            request.addPostParam("MessagingServiceSid", messagingServiceSid)
            request.setAuth(credentialSid, credentialSecret)
            // The SDK's default request retries 5xx; this overload makes exactly one attempt.
            val response =
                client.httpClient.reliableRequest(request, intArrayOf(), 1, 0) ?: return SmsDeliveryResult.AMBIGUOUS
            classify(response)
        } catch (_: Exception) {
            SmsDeliveryResult.AMBIGUOUS
        }

    private fun classify(response: Response): SmsDeliveryResult =
        when (response.statusCode) {
            429 -> {
                logRejection(response)
                SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
            }
            in 400..499 -> {
                logRejection(response)
                SmsDeliveryResult.PERMANENT_REJECTION
            }
            201 ->
                try {
                    val sid = response.stream.use { Message.fromJson(it, client.objectMapper).sid }
                    if (sid != null && sid.matches(Regex("SM[a-fA-F0-9]{32}"))) SmsDeliveryResult.ACCEPTED
                    else SmsDeliveryResult.AMBIGUOUS
                } catch (_: Exception) {
                    SmsDeliveryResult.AMBIGUOUS
                }
            else -> SmsDeliveryResult.AMBIGUOUS
        }

    private fun logRejection(response: Response) {
        val code =
            try {
                response.stream.use { client.objectMapper.readTree(it)?.get("code")?.asText()?.toIntOrNull() }
            } catch (_: Exception) {
                null
            }
        log.warn("Twilio SMS rejected: httpStatus={} providerCode={}", response.statusCode, code)
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

private fun productionTransport(): HttpClient {
    val connections =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(
                ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(3_000)).build(),
            )
            .build()
    val config =
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(3_000))
            .setResponseTimeout(Timeout.ofMilliseconds(10_000))
            .build()
    return NetworkHttpClient(
        HttpClientBuilder.create()
            .setConnectionManager(connections)
            .setDefaultRequestConfig(config)
            .disableAutomaticRetries(),
    )
}
