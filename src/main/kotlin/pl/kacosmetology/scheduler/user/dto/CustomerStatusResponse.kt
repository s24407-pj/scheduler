package pl.kacosmetology.scheduler.user.dto

/** Response DTO with block/no-show status for a customer — visible to OWNER/EMPLOYEE. */
data class CustomerStatusResponse(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val noShowCount: Int,
    val blocked: Boolean
)
