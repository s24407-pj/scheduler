package pl.kacosmetology.scheduler.user

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.security.CustomUserDetails
import pl.kacosmetology.scheduler.user.dto.UpdateUserProfileRequest
import pl.kacosmetology.scheduler.user.dto.UserProfileResponse

/** REST API for reading and updating the authenticated user's own profile. */
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService
) {

    /** Returns the authenticated user's profile. */
    @GetMapping("/me")
    fun getMyProfile(@AuthenticationPrincipal principal: CustomUserDetails?): UserProfileResponse {
        val user = principal?.user
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        return user.toProfileResponse()
    }

    /** Updates the authenticated user's profile data. */
    @PutMapping("/me")
    fun updateMyProfile(
        @Valid @RequestBody request: UpdateUserProfileRequest,
        @AuthenticationPrincipal principal: CustomUserDetails?
    ): UserProfileResponse {
        val userId = principal?.id
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Brak autoryzacji")
        return userService.updateProfile(userId, request).toProfileResponse()
    }

    private fun User.toProfileResponse() = UserProfileResponse(
        id = id,
        phoneNumber = phoneNumber,
        firstName = firstName,
        lastName = lastName,
        email = email,
        photoUrl = photoUrl
    )
}