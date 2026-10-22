package pl.kacosmetology.scheduler.auth.sms

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/** Installs the real SMS provider only for production. */
@Configuration
@Profile("prod")
class TwilioSmsConfiguration {
    /** Uses an Auth Token when configured, otherwise the API key for this account. */
    @Bean
    fun twilioSmsSender(
        @Value("\${TWILIO_ACCOUNT_SID}") accountSid: String,
        @Value("\${TWILIO_API_KEY_SID:}") apiKeySid: String,
        @Value("\${TWILIO_API_KEY_SECRET:}") apiKeySecret: String,
        @Value("\${TWILIO_AUTH_TOKEN:}") authToken: String,
        @Value("\${TWILIO_MESSAGING_SERVICE_SID}") messagingServiceSid: String,
    ): SmsSender =
        if (authToken.isNotBlank()) TwilioSmsSender(accountSid, accountSid, authToken, messagingServiceSid)
        else TwilioSmsSender(accountSid, apiKeySid, apiKeySecret, messagingServiceSid)
}
