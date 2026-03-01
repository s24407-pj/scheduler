package pl.kacosmetology.scheduler.whatsapp

/**
 * Holds all data accumulated during a WhatsApp booking conversation.
 * Stored as JSON in Redis with a 30-minute TTL.
 *
 * Dates and times are kept as ISO strings (`"yyyy-MM-dd"` / `"HH:mm"`) to avoid Jackson + Redis issues.
 */
data class ConversationState(
    val step: ConversationStep = ConversationStep.IDLE,
    val serviceId: Long? = null,
    val serviceName: String? = null,
    val employeeId: Long? = null,
    val employeeName: String? = null,
    /** Selected date as `"yyyy-MM-dd"`. */
    val date: String? = null,
    /** Selected time as `"HH:mm"`. */
    val time: String? = null,
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
