package pl.kacosmetology.scheduler.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import pl.kacosmetology.scheduler.user.User

/** Extended UserDetails holding the domain [User] entity and optional company association. */
class CustomUserDetails(
    val user: User,
    val companyId: Long?,
    private val authorities: Collection<GrantedAuthority>
) : UserDetails {

    val id: Long get() = user.id

    override fun getAuthorities(): Collection<GrantedAuthority> = authorities
    override fun getPassword(): String = user.passwordHash ?: ""
    override fun getUsername(): String = user.phoneNumber

    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = true
}