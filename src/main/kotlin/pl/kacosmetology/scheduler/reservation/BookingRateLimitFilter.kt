package pl.kacosmetology.scheduler.reservation

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.ByteArrayInputStream
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import pl.kacosmetology.scheduler.auth.RateLimitExceededException
import pl.kacosmetology.scheduler.auth.RedisRollingRateLimiter
import pl.kacosmetology.scheduler.security.CustomUserDetails
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.ObjectMapper

/** Request attribute binds raw JSON semantics before MVC validation while retaining the body for deserialization. */
const val BOOKING_PAYLOAD_ATTRIBUTE = "scheduler.bookingPayload"

/** Counts every booking submission and durably binds the key before JSON/DTO validation. */
@Component
class BookingRateLimitFilter(
    private val limits: RedisRollingRateLimiter,
    private val submissions: BookingSubmissionService,
    private val mapper: ObjectMapper,
    @param:Qualifier("handlerExceptionResolver") private val errors: HandlerExceptionResolver,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (
            request.method != "POST" ||
                request.requestURI.removePrefix(request.contextPath) !in
                    setOf("/api/reservations", "/api/reservations/staff")
        ) {
            chain.doFilter(request, response)
            return
        }
        try {
            val ip = limits.check("rate:booking:ip:${request.remoteAddr}", 60, Duration.ofMinutes(1))
            val account = SecurityContextHolder.getContext().authentication?.principal as? CustomUserDetails
            val user = account?.let {
                limits.check("rate:booking:account:${it.accountKey()}", 10, Duration.ofMinutes(1))
            }
            val limitExceeded = !ip.allowed || user?.allowed == false
            val limitFailure =
                RateLimitExceededException(
                    retryAfterSeconds = maxOf(ip.retryAfterSeconds, user?.retryAfterSeconds ?: 0),
                )
            val key = request.getHeader("Idempotency-Key")
            if (account != null && key != null) {
                val bytes = request.inputStream.readBytes()
                val payload =
                    try {
                        val value =
                            mapper
                                .readerFor(Any::class.java)
                                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                .with(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                                .readValue<Any>(bytes)
                        mapper.writeValueAsString(canonical(value))
                    } catch (_: RuntimeException) {
                        "!invalid-json:" + java.util.Base64.getEncoder().encodeToString(bytes)
                    }
                submissions.bindPayload(account.accountKey(), key, payload)
                request.setAttribute(BOOKING_PAYLOAD_ATTRIBUTE, payload)
                if (limitExceeded) throw limitFailure
                chain.doFilter(CachedBookingRequest(request, bytes), response)
            } else {
                if (limitExceeded) throw limitFailure
                chain.doFilter(request, response)
            }
        } catch (failure: Exception) {
            errors.resolveException(request, response, null, failure)
        }
    }

    private fun canonical(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to canonical(it.value) }.toSortedMap()
            is List<*> -> value.map(::canonical)
            else -> value
        }
}

private class CachedBookingRequest(request: HttpServletRequest, private val body: ByteArray) :
    HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream {
        val input = ByteArrayInputStream(body)
        return object : ServletInputStream() {
            override fun read(): Int = input.read()

            override fun isFinished(): Boolean = input.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(listener: ReadListener?) {
                listener?.onDataAvailable()
            }
        }
    }
}
