package pl.kacosmetology.scheduler.security

import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.user.UserRepository

/**
 * Loads customer-only details by phone number and exact employment-scoped staff details.
 */
@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findByPhoneNumber(username)
            ?: throw UsernameNotFoundException("User not found with phone number: $username")

        return CustomUserDetails(
            user = user,
            companyId = null,
            authorities = listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))
        )
    }

    /** Loads a principal from one employment and grants only that employment's role. */
    fun loadStaffByEmployment(username: String, employmentId: Long): CustomUserDetails {
        val user = userRepository.findByPhoneNumber(username)
            ?: throw UsernameNotFoundException("User not found with phone number: $username")
        val employment = companyEmployeeRepository.findById(employmentId).orElseThrow {
            UsernameNotFoundException("Employment not found: $employmentId")
        }
        if (employment.userId != user.id) {
            throw UsernameNotFoundException("Employment does not belong to user: $username")
        }

        return CustomUserDetails(
            user = user,
            companyId = employment.companyId,
            authorities = listOf(SimpleGrantedAuthority("ROLE_${employment.role.uppercase()}")),
            employmentId = employment.id
        )
    }
}
