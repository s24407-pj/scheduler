package pl.kacosmetology.scheduler.auth

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** RFC 6238 with the interoperable SHA1 / 6 digits / 30 seconds profile. */
@Component
class TotpCodes(private val clock: Clock) {
    private val random = SecureRandom()

    fun newSecret(): String = base32(ByteArray(20).also(random::nextBytes))

    fun recoveryCodes(): List<String> =
        List(10) {
            base32(ByteArray(16).also(random::nextBytes)).chunked(4).joinToString("-")
        }

    /** Returns the matched counter; callers persist it under the account lock to prevent replay. */
    fun match(secret: String, code: String, lastStep: Long? = null): Long? {
        if (!Regex("^[0-9]{6}$").matches(code)) return null
        val current = clock.instant().epochSecond / 30
        return listOf(current, current - 1, current + 1).firstOrNull {
            it >= 0 && it > (lastStep ?: -1) && MessageDigest.isEqual(at(secret, it).toByteArray(), code.toByteArray())
        }
    }

    fun at(secret: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decodeBase32(secret), "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = digest.last().toInt() and 15
        val value = ByteBuffer.wrap(digest, offset, 4).int and 0x7fffffff
        return (value % 1_000_000).toString().padStart(6, '0')
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        fun base32(bytes: ByteArray): String {
            var buffer = 0
            var bits = 0
            return buildString {
                for (byte in bytes) {
                    buffer = (buffer shl 8) or (byte.toInt() and 255)
                    bits += 8
                    while (bits >= 5) {
                        bits -= 5
                        append(ALPHABET[(buffer ushr bits) and 31])
                    }
                }
                if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
            }
        }

        fun decodeBase32(value: String): ByteArray {
            var buffer = 0
            var bits = 0
            val result = java.io.ByteArrayOutputStream()
            for (char in value) {
                val digit = ALPHABET.indexOf(char)
                require(digit >= 0) { "Invalid Base32" }
                buffer = (buffer shl 5) or digit
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    result.write((buffer ushr bits) and 255)
                }
            }
            return result.toByteArray()
        }
    }
}

/** AES-GCM envelopes are bound to their account or Redis lookup; ciphertexts cannot be transplanted. */
@Component
class TotpCipher(@Value("\${totp.encryption-key}") key: String, environment: org.springframework.core.env.Environment) {
    private val secretKey =
        SecretKeySpec(
            Base64.getDecoder().decode(key).also {
                require(it.size == 32) { "TOTP_ENCRYPTION_KEY must encode exactly 32 bytes" }
                require(
                    it.any { byte -> byte != 0.toByte() } ||
                        environment.activeProfiles.any { profile -> profile in setOf("dev", "test") },
                ) {
                    "The example TOTP key is permitted only in dev/test"
                }
            },
            "AES",
        )
    private val random = SecureRandom()

    fun encrypt(value: String, binding: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
        cipher.updateAAD(binding.toByteArray(Charsets.UTF_8))
        return "v1:" + Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(value: String, binding: String): String {
        require(value.startsWith("v1:"))
        val bytes = Base64.getDecoder().decode(value.removePrefix("v1:"))
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(binding.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
