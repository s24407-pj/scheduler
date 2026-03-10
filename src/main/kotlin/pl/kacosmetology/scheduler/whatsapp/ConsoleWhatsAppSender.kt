package pl.kacosmetology.scheduler.whatsapp

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Development stub for [WhatsAppSender] that logs messages to the console instead of calling the Meta API.
 * Active when `whatsapp.sender=console` (the default).
 */
@Service
@ConditionalOnProperty(name = ["whatsapp.sender"], havingValue = "console", matchIfMissing = true)
class ConsoleWhatsAppSender : WhatsAppSender {

    private val logger = LoggerFactory.getLogger(ConsoleWhatsAppSender::class.java)

    override fun sendMessage(to: String, text: String) {
        logger.info("MOCK WA to {} | {}", to, text)
    }
}
