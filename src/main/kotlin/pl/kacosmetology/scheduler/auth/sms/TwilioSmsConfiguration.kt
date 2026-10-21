package pl.kacosmetology.scheduler.auth.sms

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper

/** Installs the real SMS provider only for production. */
@Configuration
@Profile("prod")
class TwilioSmsConfiguration {
    /** Requires all four Twilio credentials and sender settings at startup. */
    @Bean
    fun twilioSmsSender(
        @Value("\${TWILIO_ACCOUNT_SID}") accountSid: String,
        @Value("\${TWILIO_API_KEY_SID}") apiKeySid: String,
        @Value("\${TWILIO_API_KEY_SECRET}") apiKeySecret: String,
        @Value("\${TWILIO_MESSAGING_SERVICE_SID}") messagingServiceSid: String,
        mapper: ObjectMapper,
    ): SmsSender = TwilioSmsSender(accountSid, apiKeySid, apiKeySecret, messagingServiceSid, mapper)
}
