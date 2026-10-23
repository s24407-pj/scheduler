package pl.kacosmetology.scheduler.config

import jakarta.servlet.FilterChain
import java.util.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestIdFilterTest {

    private val filter = RequestIdFilter()

    private fun run(incomingId: String? = null): Pair<String?, MockHttpServletResponse> {
        val request = MockHttpServletRequest()
        if (incomingId != null) request.addHeader(RequestIdFilter.HEADER, incomingId)
        val response = MockHttpServletResponse()
        var idInChain: String? = null
        val chain = FilterChain { _, _ -> idInChain = MDC.get(RequestIdFilter.MDC_KEY) }

        filter.doFilter(request, response, chain)

        return idInChain to response
    }

    @Test
    fun `should generate a UUID when no request ID is sent`() {
        val (idInChain, response) = run()

        val header = response.getHeader(RequestIdFilter.HEADER)
        UUID.fromString(header)
        assertEquals(header, idInChain)
    }

    @Test
    fun `should reuse a valid incoming request ID`() {
        val (idInChain, response) = run("abc-123")

        assertEquals("abc-123", response.getHeader(RequestIdFilter.HEADER))
        assertEquals("abc-123", idInChain)
    }

    @Test
    fun `should replace an invalid incoming request ID`() {
        val forged = "bad id\nINFO forged log line"

        val (idInChain, response) = run(forged)

        val header = response.getHeader(RequestIdFilter.HEADER)
        assertNotEquals(forged, header)
        UUID.fromString(header)
        assertEquals(header, idInChain)
    }

    @Test
    fun `should replace an over-long incoming request ID`() {
        val (_, response) = run("a".repeat(65))

        UUID.fromString(response.getHeader(RequestIdFilter.HEADER))
    }

    @Test
    fun `should clear the MDC after the request`() {
        run("abc-123")

        assertNull(MDC.get(RequestIdFilter.MDC_KEY))
    }
}
