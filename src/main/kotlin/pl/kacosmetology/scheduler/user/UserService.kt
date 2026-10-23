package pl.kacosmetology.scheduler.user

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Reads phone-only customer accounts; company profiles are handled by [CustomerService]. */
@Service
class UserService(private val userRepository: UserRepository) {
    /** Loads the current authentication identity. */
    @Transactional(readOnly = true)
    fun getProfile(userId: UUID): User =
        userRepository.findById(userId).orElseThrow { NoSuchElementException("Klient nie istnieje") }
}
