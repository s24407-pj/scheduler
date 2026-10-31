package pl.kacosmetology.scheduler.auth

import io.mockk.every
import io.mockk.isMockKMock
import jakarta.servlet.http.Cookie
import java.time.Clock
import java.time.Instant
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import tools.jackson.databind.ObjectMapper

/** Session-boundary tests complete the real TOTP endpoints, with a test-only view of the enrolled credential. */
class TestTotpLogin(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val grants: TotpGrantStore,
    private val accounts: CompanyEmployeeRepository,
    private val cipher: TotpCipher,
    private val codes: TotpCodes,
    private val clock: Clock,
) {
    fun complete(result: MvcResult, original: Cookie? = null): MvcResult {
        if (result.response.status != 200) return result
        val body = mapper.readTree(result.response.contentAsString)
        val status = body["status"]?.asString()
        if (status !in setOf("TOTP_REQUIRED", "TOTP_SETUP_REQUIRED")) return result
        val cookie = requireNotNull(result.response.getCookie("SESSION") ?: original)
        val token = body["challengeToken"].asString()
        val grant = grants.read(token, String(Base64.getDecoder().decode(cookie.value)), "test-fixture")
        val account = accounts.findById(grant.account.accountId).orElseThrow()
        val secret: String
        val path: String
        val request: MutableMap<String, String>
        if (status == "TOTP_SETUP_REQUIRED") {
            val setup = post(cookie, "/api/auth/totp/setup", mapOf("challengeToken" to token))
            assertEquals(200, setup.response.status, setup.response.contentAsString)
            val payload = mapper.readTree(setup.response.contentAsString)
            secret = payload["secret"].asString()
            path = "/api/auth/totp/setup/confirm"
            request = mutableMapOf("setupToken" to payload["setupToken"].asString())
        } else {
            secret = cipher.decrypt(requireNotNull(account.totpSecret), account.id.toString())
            path = "/api/auth/totp/verify"
            request = mutableMapOf("challengeToken" to token)
        }
        val current = clock.instant().epochSecond / 30
        val step = maxOf(current - 1, (account.totpLastStep ?: -1) + 1)
        if (step > current + 1) {
            check(isMockKMock(clock)) { "A fixed test Clock is required for repeated logins" }
            every { clock.instant() } returns Instant.ofEpochSecond(step * 30)
        }
        request["code"] = codes.at(secret, step)
        return post(cookie, path, request).also { assertEquals(200, it.response.status, it.response.contentAsString) }
    }

    private fun post(cookie: Cookie, path: String, body: Any): MvcResult {
        val csrf = mvc.perform(get("/api/auth/csrf").cookie(cookie)).andReturn().response
        val request = post(path).cookie(cookie)
        val tokenCookie = csrf.getCookie("XSRF-TOKEN")
        // Feature tests that began with csrf() retain the test repository in their session.
        // Real session-boundary tests use the actual cookie/header branch.
        if (tokenCookie == null)
            request.with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf(),
            )
        else
            request
                .cookie(tokenCookie)
                .header("X-XSRF-TOKEN", mapper.readTree(csrf.contentAsString)["token"].asString())
        return mvc.perform(request.contentType("application/json").content(mapper.writeValueAsString(body))).andReturn()
    }
}
