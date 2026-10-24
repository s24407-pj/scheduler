package pl.kacosmetology.scheduler.reservation

import jakarta.servlet.http.Cookie
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import pl.kacosmetology.scheduler.auth.AuthService
import pl.kacosmetology.scheduler.auth.RateLimitExceededException
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.dto.VerifyCodeRequest
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.security.ExpectedSessionContextFilter

/** Exercises the new local identity boundary through PostgreSQL, Redis, and authenticated HTTP requests. */
class CompanyCustomerIsolationIntegrationTest : ReservationContractTestSupport() {
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var auth: AuthService

    @Test
    fun `OTP creates separate identities for the same phone and rejects another salon code`() {
        val other = fixtures.salon()
        val phone = "+48500999001"
        auth.requestCode(RequestCodeRequest(salon.company, phone), "first")
        val code = redis.opsForValue().get("otp:${salon.company}:$phone")!!
        assertThrows<ApiException> { auth.verifyCode(VerifyCodeRequest(other.company, phone, code), "second") }
        // Changing salon does not bypass the phone-wide cooldown or consume the first salon's code.
        assertThrows<RateLimitExceededException> {
            auth.requestCode(RequestCodeRequest(other.company, phone), "second")
        }
        val first = auth.verifyCode(VerifyCodeRequest(salon.company, phone, code), "first")
        redis.delete("otp:cooldown:$phone")
        auth.requestCode(RequestCodeRequest(other.company, phone), "second")
        val next = redis.opsForValue().get("otp:${other.company}:$phone")!!
        val second = auth.verifyCode(VerifyCodeRequest(other.company, phone, next), "second")
        assertNotEquals(first.id, second.id)
        assertEquals(salon.company, first.companyId)
        assertEquals(other.company, second.companyId)
        assertNotEquals(first.accountKey(), second.accountKey())
        assertNull(customers.findById(first.id).orElseThrow().firstBookingAt)
        assertNull(customers.findById(second.id).orElseThrow().firstBookingAt)
    }

    @Test
    fun `pending local identity stays hidden until a successful first booking and remains visible after archive`() {
        val customer = salon.customer
        assertEquals(
            404,
            mvc.perform(get("/api/customers/$customer").cookie(staffCookie())).andReturn().response.status,
        )
        assertEquals(
            0,
            json
                .readTree(mvc.perform(get("/api/customers").cookie(staffCookie())).andReturn().response.contentAsString)
                .size(),
        )
        assertEquals(
            404,
            mvc.perform(get("/api/customers/$customer/visits").cookie(staffCookie())).andReturn().response.status,
        )
        for (operation in listOf("block", "unblock")) {
            val request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/customers/$customer/$operation",
                    )
                    .cookie(staffCookie())
                    .with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                            .csrf(),
                    )
            assertEquals(404, mvc.perform(request).andReturn().response.status)
        }
        for ((operation, body) in
            listOf(
                "notes" to mapOf("notes" to "hidden"),
                "profile" to mapOf("firstName" to "Hidden", "lastName" to "Customer"),
            )) {
            val request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/customers/$customer/$operation",
                    )
                    .cookie(staffCookie())
                    .with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                            .csrf(),
                    )
                    .contentType("application/json")
                    .content(json.writeValueAsString(body))
            assertEquals(404, mvc.perform(request).andReturn().response.status)
        }
        shift()
        val invalid = customerBody().toMutableMap().apply { remove("firstName") }
        assertEquals(400, book(invalid).response.status)
        assertNull(customers.findById(customer).orElseThrow().firstBookingAt)
        val booked = book()
        assertEquals(201, booked.response.status)
        assertNotNull(customers.findById(customer).orElseThrow().firstBookingAt)
        assertEquals(
            200,
            mvc.perform(get("/api/customers/$customer").cookie(staffCookie())).andReturn().response.status,
        )
        assertEquals(
            204,
            action(id(booked), "cancel-by-staff", body = mapOf("reason" to "Zmiana planów")).response.status,
        )
        assertEquals(204, action(id(booked), "archive").response.status)
        assertEquals(
            200,
            mvc.perform(get("/api/customers/$customer").cookie(staffCookie())).andReturn().response.status,
        )
    }

    @Test
    fun `logout all leaves another salon session for the same phone untouched`() {
        val other = fixtures.salon()
        val local =
            customers.save(
                pl.kacosmetology.scheduler.user.CompanyCustomer(
                    companyId = other.company,
                    phoneNumber = phone(salon.customer),
                ),
            )
        val a = customerCookie()
        val b = sessions.customer(local)
        assertEquals(204, postJson("/api/auth/logout-all", emptyMap<String, String>(), a).response.status)
        assertEquals(401, mvc.perform(get("/api/auth/me").cookie(a)).andReturn().response.status)
        assertEquals(200, mvc.perform(get("/api/auth/me").cookie(b)).andReturn().response.status)
    }

    @Test
    fun `concurrent OTP verification and staff first booking converge on one local identity`() {
        val phone = "+48500999002"
        auth.requestCode(RequestCodeRequest(salon.company, phone), "issue")
        val code = redis.opsForValue().get("otp:${salon.company}:$phone")!!
        val staff = staffCookie()
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val login =
                pool.submit<pl.kacosmetology.scheduler.security.CustomUserDetails> {
                    start.await()
                    auth.verifyCode(VerifyCodeRequest(salon.company, phone, code), "verify")
                }
            val booking =
                pool.submit<org.springframework.test.web.servlet.MvcResult> {
                    start.await()
                    manual(manualBody(phone = phone), cookie = staff)
                }
            start.countDown()
            val principal = login.get(15, java.util.concurrent.TimeUnit.SECONDS)
            val booked = booking.get(15, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(201, booked.response.status, booked.response.contentAsString)
            assertEquals(
                principal.id,
                jdbc.queryForObject("SELECT customer_id FROM reservations WHERE id=?", UUID::class.java, id(booked)),
            )
            assertNotNull(customers.findById(principal.id).orElseThrow().firstBookingAt)
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT count(*) FROM company_customers WHERE company_id=? AND phone_number=?",
                    Int::class.java,
                    salon.company,
                    phone,
                ),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `customer cannot book quote or cancel in another salon and database rejects a foreign identity`() {
        val other = fixtures.salon()
        fixtures.shift(other, future)
        val body = customerBody(service = other.service, employee = other.employee)
        assertEquals(404, book(body).response.status)
        assertEquals(
            404,
            postJson(
                    "/api/quotes",
                    mapOf("serviceId" to other.service, "startTime" to future.toString()),
                    customerCookie(),
                )
                .response
                .status,
        )
        val foreign = fixtures.reservation(other, future)
        assertEquals(404, action(foreign, "cancel", cookie = customerCookie()).response.status)
        assertThrows<DataIntegrityViolationException> {
            fixtures.reservation(other, future.plusHours(1), customer = salon.customer)
        }
        assertTrue(tree(mvc.perform(get("/api/reservations/me").cookie(customerCookie())).andReturn()).isEmpty)
    }

    @Test
    fun `customer write requires captured salon context and rejects another salon snapshot`() {
        shift()
        val fixture = customerCookie()
        val plain = Cookie("SESSION", fixture.value)
        assertEquals(400, book(cookie = plain).response.status)
        val request =
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reservations")
                .cookie(plain)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header(
                    ExpectedSessionContextFilter.HEADER,
                    "v1:${salon.customer}:${UUID.randomUUID()}:null:ROLE_CUSTOMER",
                )
                .contentType("application/json")
                .content(json.writeValueAsString(customerBody()))
        assertEquals(409, mvc.perform(request).andReturn().response.status)
        assertNull(customers.findById(salon.customer).orElseThrow().firstBookingAt)
    }
}
