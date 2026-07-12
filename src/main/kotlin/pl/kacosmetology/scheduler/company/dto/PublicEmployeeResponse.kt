package pl.kacosmetology.scheduler.company.dto

/** Minimal employee info exposed on public booking pages. [id] is the user ID used in reservation APIs. */
data class PublicEmployeeResponse(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val photoUrl: String?
)
