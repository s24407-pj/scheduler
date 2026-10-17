package pl.kacosmetology.scheduler.user.dto

import java.util.UUID

/** DTO returned by the user profile endpoints. */
data class UserProfileResponse(
    val id: UUID,
    val phoneNumber: String,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val photoUrl: String?,
)
