package pl.kacosmetology.scheduler.whatsapp

/** Abstraction for sending WhatsApp messages. Implementations: [ConsoleWhatsAppSender] and [MetaWhatsAppSender]. */
interface WhatsAppSender {
    /**
     * Sends a text message to the given WhatsApp phone number.
     *
     * @param to recipient phone number in E.164 format (e.g. `+48123456789`)
     * @param text plain-text message body
     */
    fun sendMessage(to: String, text: String)
}
