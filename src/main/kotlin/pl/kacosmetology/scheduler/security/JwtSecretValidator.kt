package pl.kacosmetology.scheduler.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * Stops startup when the JWT signing secret is shorter than 256 bits, or when the public development default is used
 * outside the `dev` and `test` profiles. A known secret lets anyone forge owner tokens.
 */
@Component
class JwtSecretValidator(
    @Value($$"${jwt.secret}") secret: String,
    environment: Environment,
) {

    companion object {
        /** Development fallback from `application.yaml`; public, so never valid in production. */
        const val DEV_DEFAULT_SECRET = "ToJestTymczasowySekretDoRozwojuAplikacjiLokalnieWymienGoNaProdukcji"
        private const val MIN_SECRET_BYTES = 32
    }

    init {
        require(secret.toByteArray().size >= MIN_SECRET_BYTES) {
            "JWT_SECRET must be at least $MIN_SECRET_BYTES bytes"
        }
        require(secret != DEV_DEFAULT_SECRET || environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            "JWT_SECRET is the public development default; set a unique secret outside the dev and test profiles"
        }
    }
}
