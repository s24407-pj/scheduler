package pl.kacosmetology.scheduler.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.time.Duration
import java.util.*
import javax.crypto.SecretKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.user.User

/** Verified claims of a signed, unexpired token. */
data class TokenClaims(
    /** Phone number of the token owner. */
    val username: String,
    /** Lowercase role: `customer`, `owner` or `employee`. */
    val role: String?,
    /** Company scope of a staff token. */
    val companyId: Long?,
    /** Employment scope of a staff token. */
    val employmentId: Long?,
    /** Session ID (`jti`) registered in [SessionStore]. */
    val sessionId: String?,
)

/** Handles JWT token generation, parsing and validation. Every issued token is registered in [SessionStore]. */
@Service
class JwtService(
    @Value($$"${jwt.secret}") private val secret: String,
    @Value($$"${jwt.expiration-ms}") private val expirationMs: Long,
    private val sessionStore: SessionStore,
) {

    private val signingKey: SecretKey by lazy { Keys.hmacShaKeyFor(secret.toByteArray()) }

    /** Generates a customer JWT without any company or employment scope. */
    fun generateCustomerToken(user: User): String = generateToken(user, mapOf("role" to "customer"))

    /** Generates a staff JWT scoped to exactly one employment row. */
    fun generateStaffToken(user: User, employment: CompanyEmployee): String {
        val employmentId = requireNotNull(employment.id) { "Employment must be persisted before issuing a token" }
        require(employment.userId == user.id) { "Employment does not belong to user" }
        return generateToken(
            user,
            mapOf(
                "employmentId" to employmentId,
                "companyId" to employment.companyId,
                "role" to employment.role.lowercase(),
            ),
        )
    }

    private fun generateToken(user: User, claims: Map<String, Any>): String {
        val userId = requireNotNull(user.id) { "User must be persisted before issuing a token" }
        val sessionId = sessionStore.create(userId, Duration.ofMillis(expirationMs))
        return Jwts.builder()
            .claims(claims)
            .subject(user.phoneNumber)
            .id(sessionId)
            .issuedAt(Date(System.currentTimeMillis()))
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(signingKey)
            .compact()
    }

    /** Verifies the signature and expiry once and returns all claims; throws if the token is invalid or expired. */
    fun parse(token: String): TokenClaims {
        val claims = extractAllClaims(token)
        return TokenClaims(
            username = claims.subject,
            role = claims["role"]?.toString(),
            companyId = claims["companyId"]?.toString()?.toLong(),
            employmentId = claims["employmentId"]?.toString()?.toLong(),
            sessionId = claims.id,
        )
    }

    /** Extracts the username (phone number) from the token subject. */
    fun extractUsername(token: String): String = parse(token).username

    /** Extracts the role claim from the token (e.g. "owner", "employee", "customer"). */
    fun extractRole(token: String): String? = parse(token).role

    /** Extracts the company ID from custom JWT claims. */
    fun extractCompanyId(token: String): Long? = parse(token).companyId

    /** Extracts the exact staff employment ID from custom JWT claims. */
    fun extractEmploymentId(token: String): Long? = parse(token).employmentId

    /** Extracts the session ID (`jti`) from the token. */
    fun extractSessionId(token: String): String? = parse(token).sessionId

    private fun extractAllClaims(token: String): Claims {
        return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).payload
    }
}
