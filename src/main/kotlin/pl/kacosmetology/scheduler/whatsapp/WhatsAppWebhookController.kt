package pl.kacosmetology.scheduler.whatsapp

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.kacosmetology.scheduler.whatsapp.dto.WebhookPayload

/**
 * Handles the Meta WhatsApp webhook: subscription verification (GET) and message ingestion (POST).
 *
 * POST always returns 200 OK — Meta requires a 200 response even when the payload cannot be processed.
 */
@RestController
@RequestMapping("/api/whatsapp/webhook")
class WhatsAppWebhookController(
    private val properties: WhatsAppProperties,
    private val conversationHandler: ConversationHandler
) {

    private val logger = LoggerFactory.getLogger(WhatsAppWebhookController::class.java)

    /**
     * Meta calls this endpoint when the webhook subscription is first registered.
     * Returns the challenge string on success, or 403 if the token does not match.
     */
    @GetMapping
    fun verify(
        @RequestParam("hub.mode") mode: String?,
        @RequestParam("hub.verify_token") verifyToken: String?,
        @RequestParam("hub.challenge") challenge: String?
    ): ResponseEntity<String> {
        return if (mode == "subscribe" && verifyToken == properties.verifyToken) {
            ResponseEntity.ok(challenge ?: "")
        } else {
            ResponseEntity.status(403).build()
        }
    }

    /**
     * Receives inbound messages from Meta. Processes only `text`-type messages.
     * All other types and any processing errors are silently ignored.
     */
    @PostMapping
    fun receive(@RequestBody payload: WebhookPayload): ResponseEntity<Void> {
        try {
            payload.entry.forEach { entry ->
                entry.changes.forEach { change ->
                    change.value.messages?.forEach { message ->
                        if (message.type == "text" && message.text != null) {
                            try {
                                conversationHandler.handle(message.from, message.text.body)
                            } catch (e: Exception) {
                                logger.error("Error handling WhatsApp message from {}: {}", message.from, e.message, e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing WhatsApp webhook payload: {}", e.message, e)
        }
        return ResponseEntity.ok().build()
    }
}
