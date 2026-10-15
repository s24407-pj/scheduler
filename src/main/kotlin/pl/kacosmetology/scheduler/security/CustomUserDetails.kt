package pl.kacosmetology.scheduler.security

import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.server.ResponseStatusException

/** Immutable, session-safe identity. No domain entity, password, or session identifier is retained. */
data class CustomUserDetails(
    val id: Long,
    private val phoneNumber: String,
    val role: String,
    val companyId: Long? = null,
    val employmentId: Long? = null,
) : UserDetails {
    /** Returns the exact staff company scope or rejects an unscoped principal. */
    fun requireCompanyId(): Long = companyId ?: throw ResponseStatusException(HttpStatus.FORBIDDEN)

    /** Separates global customer accounts from each company's independent staff account. */
    fun accountKey(): String =
        when (role) {
            "ROLE_CUSTOMER" -> "customer:$id"
            "ROLE_OWNER",
            "ROLE_EMPLOYEE" -> "staff:${requireNotNull(employmentId)}"
            else -> error("Unsupported authentication realm")
        }

    override fun getAuthorities() = listOf(SimpleGrantedAuthority(role))

    override fun getPassword(): String = ""

    override fun getUsername(): String = phoneNumber

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = true

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
