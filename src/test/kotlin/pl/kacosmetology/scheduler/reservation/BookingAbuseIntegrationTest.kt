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
    fun `rate limited fresh keys cannot grow permanent submission storage`() {
        val cookie = customerCookie()
        repeat(10) { assertEquals(409, request(cookie).response.status) }
        val before = count("booking_submissions")
        repeat(20) { assertEquals(429, request(cookie, key = "limited-$it").response.status) }
        assertEquals(before, count("booking_submissions"))
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `oversized booking body is rejected before binding with and without a key`() {
        for (key in listOf("oversized", null)) {
            val request =
                post("/api/reservations")
                    .cookie(customerCookie())
                    .with(csrf())
                    .contentType("application/json")
                    .content("x".repeat(MAX_BOOKING_BODY_BYTES + 1))
            key?.let { request.header("Idempotency-Key", it) }
            val result = mvc.perform(request).andReturn()
            assertEquals(413, result.response.status, result.response.contentAsString)
            assertEquals("PAYLOAD_TOO_LARGE", tree(result)["code"].asString())
        }
        assertEquals(0L, count("booking_submissions"))
    }

    @Test
    fun `admitted malformed input still binds a permanent fingerprint`() {
        val cookie = customerCookie()
        val malformed =
            mvc.perform(
                    post("/api/reservations")
                        .cookie(cookie)
                        .with(csrf())
                        .header("Idempotency-Key", "malformed")
                        .contentType("application/json")
                        .content("{invalid"),
                )
                .andReturn()
        assertEquals(400, malformed.response.status)
        val changed = request(cookie, key = "malformed")
        assertEquals(409, changed.response.status)
        assertEquals("IDEMPOTENCY_CONFLICT", tree(changed)["code"].asString())
        assertEquals(1L, count("booking_submissions"))
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

    private fun request(
        cookie: Cookie,
        path: String = "/api/reservations",
        body: Any = customerBody(),
        key: String = "retry-limit",
    ) =
        mvc.perform(
                post(path)
                    .servletPath(path)
                    .cookie(cookie)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)),
            )
            .andReturn()
}
