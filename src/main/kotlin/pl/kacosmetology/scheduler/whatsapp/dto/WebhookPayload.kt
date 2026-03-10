package pl.kacosmetology.scheduler.whatsapp.dto

import com.fasterxml.jackson.annotation.JsonProperty

/** Root payload sent by Meta to the webhook endpoint. */
data class WebhookPayload(
    /** Always `"whatsapp_business_account"` for WhatsApp webhooks. */
    @JsonProperty("object") val objectType: String = "",
    val entry: List<Entry> = emptyList()
)

/** Top-level entry in the webhook payload. */
data class Entry(
    val changes: List<Change> = emptyList()
)

/** A single change event within an [Entry]. */
data class Change(
    val value: ChangeValue = ChangeValue()
)

/** Value object containing the list of inbound messages. */
data class ChangeValue(
    val messages: List<Message>? = null
)

/** A single inbound WhatsApp message. */
data class Message(
    /** Sender's phone number without leading `+`, e.g. `"48123456789"`. */
    val from: String = "",
    /** Message type, e.g. `"text"`, `"image"`, `"audio"`. */
    val type: String = "",
    /** Present when [type] is `"text"`. */
    val text: TextBody? = null
)

/** Text body of a text-type [Message]. */
data class TextBody(
    val body: String = ""
)
