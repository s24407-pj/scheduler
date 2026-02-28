package pl.kacosmetology.scheduler.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.user.dto.UpdateUserProfileRequest

/** Business logic for user profile management. */
@Service
class UserService(
    private val userRepository: UserRepository
) {

    /** Updates the user's first name, last name and email. Validates email uniqueness. */
    @Transactional
    fun updateProfile(userId: Long, request: UpdateUserProfileRequest): User {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("Użytkownik nie istnieje") }

        if (request.email != null && request.email != user.email) {
            if (userRepository.findByEmail(request.email) != null) {
                throw IllegalStateException("Ten adres e-mail jest już zajęty")
            }
        }

        user.firstName = request.firstName ?: user.firstName
        user.lastName = request.lastName ?: user.lastName
        user.email = request.email

        return userRepository.save(user)
    }
}