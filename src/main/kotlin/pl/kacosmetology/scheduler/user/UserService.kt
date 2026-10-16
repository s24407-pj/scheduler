package pl.kacosmetology.scheduler.user

import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.user.dto.UpdateUserProfileRequest

/** Reads phone-only customer accounts; company profiles are handled by [CustomerService]. */
@Service
class UserService(private val userRepository: UserRepository) {
    /** Loads the current authentication identity. */
    @Transactional(readOnly = true)
    fun getProfile(userId: Long): User =
        userRepository.findById(userId).orElseThrow { NoSuchElementException("Klient nie istnieje") }

    /**
     * Legacy internal entry point retained temporarily for source compatibility; global profile edits are forbidden.
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateProfile(userId: Long, request: UpdateUserProfileRequest): User =
        throw AccessDeniedException("Profil klienta edytuje personel salonu")
}
