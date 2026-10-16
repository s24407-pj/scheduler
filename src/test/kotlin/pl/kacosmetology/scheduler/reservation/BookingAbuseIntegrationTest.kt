package pl.kacosmetology.scheduler.reservation

import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/** Atomic booking request limits include rejected submissions and idempotent retries. */
class BookingAbuseIntegrationTest : ReservationContractTestSupport() {
    @Test
    fun `customer retries count toward account limit and return Retry After`() {
        val cookie = customerCookie()
        repeat(10) { assertEquals(409, request(cookie).response.status) }
        val limited = request(cookie)
        assertEquals(429, limited.response.status)
        assertTrue(limited.response.getHeader("Retry-After")!!.toLong() > 0)
        assertEquals("RATE_LIMITED", tree(limited)["code"].stringValue())
    }

    @Test
    fun `staff failed validation counts toward account limit`() {
        val cookie = staffCookie()
        repeat(10) {
            assertEquals(400, request(cookie, "/api/reservations/staff", emptyMap<String, String>()).response.status)
        }
        val limited = request(cookie, "/api/reservations/staff", emptyMap<String, String>())
        assertEquals(429, limited.response.status)
        assertNotNull(limited.response.getHeader("Retry-After"))
    }

    @Test
    fun `IP limit spans independent customer accounts`() {
        val cookies = (1..7).map { customerCookie(newCustomer()) }
        for (cookie in cookies.take(6)) repeat(10) { assertEquals(409, request(cookie).response.status) }
        val limited = request(cookies.last())
        assertEquals(429, limited.response.status)
        assertTrue(limited.response.getHeader("Retry-After")!!.toLong() > 0)
    }

    private fun request(cookie: Cookie, path: String = "/api/reservations", body: Any = customerBody()) =
        mvc.perform(
                post(path)
                    .servletPath(path)
                    .cookie(cookie)
                    .with(csrf())
                    .header("Idempotency-Key", "retry-limit")
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)),
            )
            .andReturn()
}
