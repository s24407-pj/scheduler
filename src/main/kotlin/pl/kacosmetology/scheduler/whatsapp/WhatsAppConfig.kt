package pl.kacosmetology.scheduler.whatsapp

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Configuration properties for the WhatsApp integration. */
@ConfigurationProperties(prefix = "whatsapp")
data class WhatsAppProperties(
    /** Token used to verify the Meta webhook subscription. */
    val verifyToken: String = "dev_verify_token",
    /** Meta Graph API access token for sending messages. */
    val accessToken: String = "",
    /** WhatsApp Business Phone Number ID used in the Graph API URL. */
    val phoneNumberId: String = "",
    /** Company ID to scope services, employees and reservations. */
    val companyId: Long = 1L,
    /** Sender implementation: `"console"` (dev stub) or `"meta"` (live). */
    val sender: String = "console"
)

/** Registers [WhatsAppProperties] configuration properties. */
@Configuration
@EnableConfigurationProperties(WhatsAppProperties::class)
class WhatsAppConfig
