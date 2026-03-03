package pl.kacosmetology.scheduler.security

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Service
import java.util.*
import javax.crypto.SecretKey

/** Handles JWT token generation, parsing and validation. */
@Service
class JwtService(
    @Value($$"${jwt.secret}") private val secret: String,
    @Value($$"${jwt.expiration-ms}") private val expirationMs: Long
) {

    private val signingKey: SecretKey by lazy { Keys.hmacShaKeyFor(secret.toByteArray()) }

    /** Generates a JWT token with the user's identity, optional company ID, and role in claims. */
    fun generateToken(userDetails: UserDetails, companyId: Long?): String {
        val claims = mutableMapOf<String, Any>()
        if (companyId != null) {
            claims["companyId"] = companyId
        }
        val role = userDetails.authorities.firstOrNull()?.authority?.removePrefix("ROLE_")?.lowercase()
        if (role != null) {
            claims["role"] = role
        }

        return Jwts.builder()
            .claims(claims)
            .subject(userDetails.username)
            .issuedAt(Date(System.currentTimeMillis()))
            .expiration(Date(System.currentTimeMillis() + expirationMs))
            .signWith(signingKey)
            .compact()
    }

    /** Extracts the username (phone number) from the token subject. */
    fun extractUsername(token: String): String {
        return extractAllClaims(token).subject
    }

    /** Extracts the company ID from custom JWT claims. */
    fun extractCompanyId(token: String): Long? {
        return extractAllClaims(token)["companyId"]?.toString()?.toLong()
    }

    /** Validates that the token belongs to the given user and has not expired. */
    fun isTokenValid(token: String, userDetails: UserDetails): Boolean {
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