package pl.kacosmetology.scheduler.security

import java.util.*
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.user.CompanyCustomerRepository

/** Loads customer-only details by phone number and exact employment-scoped staff details. */
@Service
class CustomUserDetailsService(
    private val userRepository: CompanyCustomerRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
) : UserDetailsService {

    /** Phone alone is deliberately insufficient; also prevents Boot from provisioning a fallback user. */
    override fun loadUserByUsername(username: String): UserDetails =
        throw UsernameNotFoundException("Company-scoped authentication required")

    /** Never resolves a customer by phone alone: exact identity and salon must still match. */
    fun loadCustomer(username: String, companyId: UUID, customerId: UUID): CustomUserDetails {
        val customer =
            userRepository.findByCompanyIdAndId(companyId, customerId)
                ?: throw UsernameNotFoundException("Customer account unavailable")
        if (customer.phoneNumber != username) throw UsernameNotFoundException("Customer account unavailable")
        return CustomUserDetails(requireNotNull(customer.id), customer.phoneNumber, "ROLE_CUSTOMER", companyId)
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
