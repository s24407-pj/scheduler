package pl.kacosmetology.scheduler.whatsapp

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/**
 * Production [WhatsAppSender] that calls the Meta Graph API to deliver messages.
 * Active when `whatsapp.sender=meta`.
 */
@Service
@ConditionalOnProperty(name = ["whatsapp.sender"], havingValue = "meta")
class MetaWhatsAppSender(private val properties: WhatsAppProperties) : WhatsAppSender {

    private val logger = LoggerFactory.getLogger(MetaWhatsAppSender::class.java)
    private val restClient = RestClient.create()

    /**
     * Sends a text message via the Meta Cloud API.
     * Errors are logged but never propagated — message delivery is a side-effect.
     */
    override fun sendMessage(to: String, text: String) {
        try {
            val url = "https://graph.facebook.com/v21.0/${properties.phoneNumberId}/messages"
            val body = mapOf(
                "messaging_product" to "whatsapp",
                "to" to to,
                "type" to "text",
                "text" to mapOf("body" to text)
            )
            restClient.post()
                .uri(url)
                .header("Authorization", "Bearer ${properties.accessToken}")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            logger.error("Failed to send WhatsApp message to {}: {}", to, e.message, e)
        }
    }
}
