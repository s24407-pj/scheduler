package pl.kacosmetology.scheduler.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.user.UserRepository

/**
 * Loads user details by phone number for Spring Security authentication.
 * Builds authorities based on the user's company employment roles.
 * The companyId is left null here — it gets populated from the JWT in [JwtAuthenticationFilter].
 */
@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository
) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findByPhoneNumber(username)
            ?: throw UsernameNotFoundException("User not found with phone number: $username")

        val authorities = mutableListOf<GrantedAuthority>(SimpleGrantedAuthority("ROLE_CUSTOMER"))

        companyEmployeeRepository.findAllByUserId(user.id).forEach { employment ->
            authorities.add(SimpleGrantedAuthority("ROLE_${employment.role.uppercase()}"))
        }

        return CustomUserDetails(user = user, companyId = null, authorities = authorities)
    }
}