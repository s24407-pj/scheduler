package pl.kacosmetology.scheduler.user

import java.util.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Reads company-local customer accounts; staff-visible profiles are handled by [CustomerService]. */
@Service
class UserService(private val userRepository: CompanyCustomerRepository) {
    /** Loads the current authentication identity. */
    @Transactional(readOnly = true)
    fun getProfile(userId: UUID, companyId: UUID): CompanyCustomer =
        userRepository.findByCompanyIdAndId(companyId, userId) ?: throw NoSuchElementException("Klient nie istnieje")
}
