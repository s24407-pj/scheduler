package pl.kacosmetology.scheduler.security

import java.util.UUID

/** Presentation used to prepare an independent staff employment in older feature fixtures. */
data class StaffFixture(
    var id: UUID? = UUID.randomUUID(),
    val phoneNumber: String,
    var firstName: String = "",
    var lastName: String = "",
    var email: String? = null,
    val passwordHash: String? = null,
    var photoUrl: String? = null,
)
