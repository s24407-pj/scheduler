package pl.kacosmetology.scheduler.whatsapp

import java.time.LocalDate
import java.time.LocalTime

/**
 * Holds all data accumulated during a WhatsApp booking conversation.
 * Stored as JSON in Redis with a 30-minute TTL.
 */
data class ConversationState(
    val step: ConversationStep = ConversationStep.IDLE,
    val serviceId: Long? = null,
    val serviceName: String? = null,
    val employeeId: Long? = null,
    val employeeName: String? = null,
    /** Selected date. */
    val date: LocalDate? = null,
    /** Selected time. */
    val time: LocalTime? = null,
    /** First name collected during the new-client sub-flow. */
    val pendingFirstName: String? = null,
    /** Maps option index (1-based) to service ID. */
    val serviceOptions: List<Long> = emptyList(),
    /** Maps option index (1-based) to employee user ID. */
    val employeeOptions: List<Long> = emptyList(),
    /** Maps option index (1-based) to date string (`"yyyy-MM-dd"`). */
    val dateOptions: List<String> = emptyList(),
    /** Maps option index (1-based) to time string (`"HH:mm"`). */
    val timeOptions: List<String> = emptyList()
)
