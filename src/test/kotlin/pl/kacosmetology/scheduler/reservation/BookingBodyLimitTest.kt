package pl.kacosmetology.scheduler.reservation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequestWrapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import pl.kacosmetology.scheduler.auth.RateLimitDecision
import pl.kacosmetology.scheduler.auth.RedisRollingRateLimiter
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import tools.jackson.databind.json.JsonMapper

class BookingBodyLimitTest {
    @Test
    fun `chunked oversized input reads at most the bounded prefix and never binds a key`() {
        val limits = mockk<RedisRollingRateLimiter>()
        every { limits.check(any(), any(), any()) } returns RateLimitDecision(true, 0)
        val submissions = mockk<BookingSubmissionService>()
        val errors = HandlerExceptionResolver { _, response, _, failure ->
            assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, (failure as ApiException).code)
            response.status = 413
            ModelAndView()
        }
        val original =
            MockHttpServletRequest("POST", "/api/reservations").apply {
                setContent(ByteArray(MAX_BOOKING_BODY_BYTES * 3))
            }
        val stream = original.inputStream
        val chunked =
            object : HttpServletRequestWrapper(original) {
                override fun getContentLengthLong() = -1L

                override fun getInputStream() = stream
            }
        val response = MockHttpServletResponse()
        BookingRateLimitFilter(limits, submissions, JsonMapper.builder().build(), errors).doFilter(chunked, response) {
            _,
            _ ->
            fail<Unit>("Oversized input reached MVC")
        }
        assertEquals(413, response.status)
        assertEquals(MAX_BOOKING_BODY_BYTES * 2 - 1, stream.available())
        verify(exactly = 0) { submissions.bindPayload(any(), any(), any()) }
    }
}
