package pl.kacosmetology.scheduler.whatsapp

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Development [WhatsAppSender] that logs messages to the console AND captures them in a [ThreadLocal]
 * so that [WhatsAppSimulateController] can return bot replies synchronously.
 *
 * Active when `whatsapp.sender=dev`.
 */
@Service
@ConditionalOnProperty(name = ["whatsapp.sender"], havingValue = "dev")
class RecordingWhatsAppSender : WhatsAppSender {

    private val logger = LoggerFactory.getLogger(RecordingWhatsAppSender::class.java)
    private val capture = ThreadLocal.withInitial<MutableList<String>?> { null }

    /** Starts capturing outbound messages for the current thread. */
    fun startCapture() {
        capture.set(mutableListOf())
    }

    /** Stops capturing and returns all messages collected since [startCapture]. Clears the thread-local. */
    fun stopCapture(): List<String> {
        val recorded = capture.get() ?: emptyList()
        capture.remove()
        return recorded
    }

    override fun sendMessage(to: String, text: String) {
        logger.info("MOCK WA to {} | {}", to, text)
        capture.get()?.add(text)
    }
}
