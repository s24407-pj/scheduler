package pl.kacosmetology.scheduler.user.dto

/** DTO returned by the user profile endpoints. */
data class UserProfileResponse(
    val id: Long,
    val phoneNumber: String,
    val firstName: String,
    val lastName: String,
    val email: String?
)

