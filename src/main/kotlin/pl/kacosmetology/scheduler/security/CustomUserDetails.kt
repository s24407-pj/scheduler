package pl.kacosmetology.scheduler.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.user.User

/**
 * Extended UserDetails holding the domain [User], an optional, exact staff employment scope, and the
 * [sessionId] of the token that authenticated the request.
 */
class CustomUserDetails(
    val user: User,
    val companyId: Long?,
    private val authorities: Collection<GrantedAuthority>,
    val employmentId: Long? = null,
    val sessionId: String? = null
) : UserDetails {

    /** Returns a copy bound to the session of the authenticating token. */
    fun withSession(sessionId: String) = CustomUserDetails(user, companyId, authorities, employmentId, sessionId)

    /** Persisted domain user identifier required by authenticated principals. */
    val id: Long get() = requireNotNull(user.id) { "Authenticated user must have a persisted ID" }

    /** Returns the staff company scope or rejects an unscoped principal with HTTP 403. */
    fun requireCompanyId(): Long = companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)

    override fun getAuthorities(): Collection<GrantedAuthority> = authorities
    override fun getPassword(): String = user.passwordHash ?: ""
    override fun getUsername(): String = user.phoneNumber

    override fun isAccountNonExpired(): Boolean = true
    override fun isAccountNonLocked(): Boolean = true
    override fun isCredentialsNonExpired(): Boolean = true
    override fun isEnabled(): Boolean = true
}
