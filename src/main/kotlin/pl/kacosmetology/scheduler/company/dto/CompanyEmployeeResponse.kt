package pl.kacosmetology.scheduler.company.dto

/** Represents a single employee of a company, including their basic user info and role. */
data class CompanyEmployeeResponse(
    val id: Long,
    val userId: Long,
    val firstName: String,
    val lastName: String,
    val role: String,
    val photoUrl: String?,
    val loginEmail: String? = null,
    val contactPhone: String? = null,
    val enabled: Boolean = true,
)
