package pl.kacosmetology.scheduler.company.dto

import java.util.*

/** Minimal employee info exposed on public booking pages. [id] is the user ID used in reservation APIs. */
data class PublicEmployeeResponse(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val photoUrl: String?,
)
