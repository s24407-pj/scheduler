package pl.kacosmetology.scheduler.company.dto

import java.util.UUID
import pl.kacosmetology.scheduler.company.EmployeeEligibilityMode

/** Represents a single employee of a company, including their basic user info and role. */
data class CompanyEmployeeResponse(
    val id: UUID,
    val userId: UUID,
    val firstName: String,
    val lastName: String,
    val role: String,
    val photoUrl: String?,
    val loginEmail: String? = null,
    val contactPhone: String? = null,
    val enabled: Boolean = true,
    val eligibilityMode: EmployeeEligibilityMode,
)
