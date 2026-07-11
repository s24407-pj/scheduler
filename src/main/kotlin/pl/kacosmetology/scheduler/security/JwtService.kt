package pl.kacosmetology.scheduler.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.user.User
import java.util.*
import javax.crypto.SecretKey

/** Handles JWT token generation, parsing and validation. */
@Service
class JwtService(
    @Value($$"${jwt.secret}") private val secret: String,
    @Value($$"${jwt.expiration-ms}") private val expirationMs: Long
) {

    private val signingKey: SecretKey by lazy { Keys.hmacShaKeyFor(secret.toByteArray()) }

    /** Generates a customer JWT without any company or employment scope. */
    fun generateCustomerToken(user: User): String = generateToken(user.phoneNumber, mapOf("role" to "customer"))

    /** Generates a staff JWT scoped to exactly one employment row. */
    fun generateStaffToken(user: User, employment: CompanyEmployee): String {
        val employmentId = requireNotNull(employment.id) { "Employment must be persisted before issuing a token" }
        require(employment.userId == user.id) { "Employment does not belong to user" }
        return generateToken(
            user.phoneNumber,
            mapOf(
                "employmentId" to employmentId,
                "companyId" to employment.companyId,
                "role" to employment.role.lowercase()
            )
        )
    }

    private fun generateToken(subject: String, claims: Map<String, Any>): String {
        return Jwts.builder()
            .claims(claims)
            .subject(subject)
            .issuedAt(Date(System.currentTimeMillis()))
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(signingKey)
            .compact()
    }

    /** Extracts the username (phone number) from the token subject. */
    fun extractUsername(token: String): String {
        return extractAllClaims(token).subject
    }

    /** Extracts the role claim from the token (e.g. "owner", "employee", "customer"). */
    fun extractRole(token: String): String? {
        return extractAllClaims(token)["role"]?.toString()
    }

    /** Extracts the company ID from custom JWT claims. */
    fun extractCompanyId(token: String): Long? {
        return extractAllClaims(token)["companyId"]?.toString()?.toLong()
    }

    /** Extracts the exact staff employment ID from custom JWT claims. */
    fun extractEmploymentId(token: String): Long? {
        return extractAllClaims(token)["employmentId"]?.toString()?.toLong()
    }

    /** Validates that the token belongs to the given user and has not expired. */
    fun isTokenValid(token: String, userDetails: CustomUserDetails): Boolean {
        val username = extractUsername(token)
        return (username == userDetails.username) && !isTokenExpired(token)
    }

    private fun isTokenExpired(token: String): Boolean {
        return extractAllClaims(token).expiration.before(Date())
    }

    private fun extractAllClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload
    }
}
