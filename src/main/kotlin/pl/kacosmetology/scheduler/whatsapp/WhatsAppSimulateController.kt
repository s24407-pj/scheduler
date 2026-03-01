package pl.kacosmetology.scheduler.whatsapp

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Development-only controller that simulates WhatsApp message delivery and returns bot replies
 * synchronously — without going through Meta or ngrok.
 *
 * Active only when `whatsapp.sender=dev`.
 */
@RestController
@ConditionalOnProperty(name = ["whatsapp.sender"], havingValue = "dev")
@RequestMapping("/api/whatsapp/simulate")
class WhatsAppSimulateController(
    private val handler: ConversationHandler,
    private val recordingSender: RecordingWhatsAppSender,
    private val conversationStore: ConversationStore
) {

    /** Incoming message from the simulated user. */
    data class SimulateRequest(val from: String, val text: String)

    /** Bot replies collected during handling of the simulated message. */
    data class SimulateResponse(val from: String, val messages: List<String>)

    /**
     * Simulates a WhatsApp message from [SimulateRequest.from] with body [SimulateRequest.text].
     * Returns all messages the bot sent back during processing.
     */
    @PostMapping
    fun simulate(@RequestBody req: SimulateRequest): SimulateResponse {
        val normalized = if (req.from.startsWith("+")) req.from else "+${req.from}"
        recordingSender.startCapture()
        handler.handle(normalized, req.text)
        return SimulateResponse(from = normalized, messages = recordingSender.stopCapture())
    }

    /**
     * Resets the conversation state for [phone] in Redis, allowing a fresh conversation to start.
     */
    @DeleteMapping("/{phone}")
    fun reset(@PathVariable phone: String): ResponseEntity<Void> {
        val normalized = if (phone.startsWith("+")) phone else "+$phone"
        conversationStore.delete(normalized)
        return ResponseEntity.noContent().build()
    }
}
