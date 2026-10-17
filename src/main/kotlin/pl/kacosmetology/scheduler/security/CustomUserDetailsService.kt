package pl.kacosmetology.scheduler.security

import java.util.UUID
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.user.UserRepository

/** Loads customer-only details by phone number and exact employment-scoped staff details. */
@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user =
            userRepository.findByPhoneNumber(username)
                ?: throw UsernameNotFoundException("User not found with phone number: $username")

        return CustomUserDetails(
            id = requireNotNull(user.id),
            phoneNumber = user.phoneNumber,
            companyId = null,
            role = "ROLE_CUSTOMER",
        )
    }

    /** Loads a principal from one employment and grants only that employment's role. */
    fun loadStaffByEmployment(username: String, employmentId: UUID): CustomUserDetails {
        val employment =
            companyEmployeeRepository.findById(employmentId).orElseThrow {
                UsernameNotFoundException("Staff account not found")
            }
        if (!employment.enabled || employment.loginEmail != username) {
            throw UsernameNotFoundException("Staff account unavailable")
        }

        if (employment.role !in setOf("OWNER", "EMPLOYEE")) {
            throw UsernameNotFoundException("Unsupported employment role")
        }
        return CustomUserDetails(
            id = employment.userId,
            phoneNumber = requireNotNull(employment.loginEmail),
            companyId = employment.companyId,
            role = "ROLE_${employment.role.uppercase()}",
            employmentId = employment.id,
            authVersion = employment.authVersion,
        )
    }
}
