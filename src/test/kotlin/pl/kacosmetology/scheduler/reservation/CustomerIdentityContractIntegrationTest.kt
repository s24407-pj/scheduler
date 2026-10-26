package pl.kacosmetology.scheduler.reservation

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.queryForObject
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

/** Association intent, readiness races and permanent replay are checked against the real PostgreSQL/Redis boundary. */
class CustomerIdentityContractIntegrationTest : ReservationContractTestSupport() {
    private fun newSelection(phone: String = phone(salon.customer)) =
        mapOf("kind" to "new", "phoneNumber" to phone, "firstName" to "Ewa", "lastName" to "Nowak")

    @Test
    fun `new mode cannot silently select established customer or overwrite names`() {
        profile()
        val result = manual(manualBody() + mapOf("customer" to newSelection()))
        assertEquals(409, result.response.status, result.response.contentAsString)
        assertEquals("CUSTOMER_PHONE_ALREADY_EXISTS", tree(result)["code"].asString())
        assertEquals(salon.customer.toString(), tree(result)["existingCustomer"]["id"].asString())
        assertEquals("Jan", customers.findById(salon.customer).orElseThrow().firstName)
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `existing mode requires established local ID and forbids mixing identity selectors`() {
        val pending =
            manual(manualBody() + mapOf("customer" to mapOf("kind" to "existing", "customerId" to salon.customer)))
        assertEquals(404, pending.response.status)
        val other = fixtures.salon()
        fixtures.profile(other)
        assertEquals(
            404,
            manual(manualBody() + mapOf("customer" to mapOf("kind" to "existing", "customerId" to other.customer)))
                .response
                .status,
        )
        profile()
        assertEquals(
            400,
            manual(
                    manualBody() +
                        mapOf(
                            "customer" to
                                mapOf(
                                    "kind" to "existing",
                                    "customerId" to salon.customer,
                                    "phoneNumber" to phone(salon.customer),
                                ),
                        ),
                )
                .response
                .status,
        )
        assertEquals(400, manual(manualBody() + mapOf("customerPhone" to phone(salon.customer))).response.status)
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `staff establishes pending identity and self must explicitly acknowledge changed readiness`() {
        shift()
        val cookie = customerCookie()
        val before = mvc.perform(get("/api/users/me").cookie(cookie)).andReturn()
        assertEquals("PENDING", tree(before)["profileState"].asString())
        assertEquals(salon.company.toString(), tree(before)["companyId"].asString())
        assertFalse(tree(before).has("firstName"))
        val staff = manual(manualBody(future.plusHours(1)) + mapOf("customer" to newSelection()))
        assertEquals(201, staff.response.status, staff.response.contentAsString)
        assertEquals(salon.customer.toString(), tree(staff)["customerId"].asString())
        val conflict = book(customerBody(), cookie = cookie)
        assertEquals(409, conflict.response.status)
        assertEquals("CUSTOMER_PROFILE_CHANGED", tree(conflict)["code"].asString())
        assertEquals(1L, count("reservations"))
        val confirmed = book(customerBody() - "firstVisitDetails", cookie = cookie)
        assertEquals(201, confirmed.response.status, confirmed.response.contentAsString)
        assertFalse(tree(confirmed).has("customerId"))
        assertEquals("Ewa", customers.findById(salon.customer).orElseThrow().firstName)
        val after = mvc.perform(get("/api/users/me").cookie(cookie)).andReturn()
        assertEquals("ESTABLISHED", tree(after)["profileState"].asString())
    }

    @Test
    fun `successful first details replay bypasses readiness conflict and returns current cancelled outcome`() {
        shift()
        val body = customerBody()
        val key = UUID.randomUUID().toString()
        val created = book(body, key)
        assertEquals(201, created.response.status)
        assertEquals(
            204,
            action(id(created), "cancel-by-staff", body = mapOf("reason" to "Zmiana planu")).response.status,
        )
        val replay = book(body, key)
        assertEquals(200, replay.response.status, replay.response.contentAsString)
        assertEquals(id(created), id(replay))
        assertEquals("CANCELLED", tree(replay)["status"].asString())
        val separateAttempt = book(body)
        assertEquals(409, separateAttempt.response.status)
        assertEquals("CUSTOMER_PROFILE_CHANGED", tree(separateAttempt)["code"].asString())
        assertEquals(1L, count("reservations"))
    }

    @Test
    fun `new staff intent replay precedes its own established collision`() {
        val body = manualBody() + mapOf("customer" to newSelection())
        val key = UUID.randomUUID().toString()
        val created = manual(body, key)
        assertEquals(201, created.response.status)
        val replay = manual(body, key)
        assertEquals(200, replay.response.status)
        assertEquals(id(created), id(replay))
        assertEquals(409, manual(body).response.status)
        assertEquals(1L, count("reservations"))
        assertEquals(1L, count("reservation_audit"))
    }

    @Test
    fun `flat booking fields are rejected and never create a booking`() {
        val self = (customerBody() - "firstVisitDetails") + mapOf("firstName" to "Jan", "lastName" to "Klient")
        val staff = (manualBody() - "customer") + mapOf("customerPhone" to phone(salon.customer))
        assertEquals(400, book(self).response.status)
        assertEquals(400, manual(staff).response.status)
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `profile revision increments on establishment and rejects stale full edits`() {
        val established = manual(manualBody())
        assertEquals(201, established.response.status)
        val cookie = staffCookie()
        val detail = mvc.perform(get("/api/customers/${salon.customer}").cookie(cookie)).andReturn()
        val revision = tree(detail)["revision"].longValue()
        assertTrue(revision > 0)
        fun update(expected: Long?) =
            mvc.perform(
                    put("/api/customers/${salon.customer}/profile")
                        .with(csrf())
                        .cookie(cookie)
                        .apply { expected?.let { header("If-Match", "\"$it\"") } }
                        .contentType("application/json")
                        .content("""{"firstName":"Anna","lastName":"Changed","contactEmail":null}"""),
                )
                .andReturn()
        assertEquals(400, update(null).response.status)
        assertEquals(409, update(revision - 1).response.status)
        assertEquals(204, update(revision).response.status)
        assertEquals(409, update(revision).response.status)
        assertEquals("Anna", customers.findById(salon.customer).orElseThrow().firstName)
        assertEquals(
            revision + 1,
            jdbc.queryForObject<Long>("SELECT version FROM company_customers WHERE id=?", salon.customer),
        )
    }

    @Test
    fun `international syntax is authoritative and failed staff creation leaves no identity`() {
        for (phone in listOf("500123456", "+0500123456", "+48 500123456", "+12345678", "+1234567890123456")) {
            assertEquals(400, manual(manualBody() + mapOf("customer" to newSelection(phone))).response.status)
        }
        assertEquals(1L, count("company_customers"))
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `staff and customer first booking race establishes exactly one agreed profile`() {
        shift()
        val customer = customerCookie()
        val staff = staffCookie()
        val results =
            concurrent(
                { book(customerBody(), cookie = customer) },
                { manual(manualBody(future.plusHours(1)) + mapOf("customer" to newSelection()), cookie = staff) },
            )
        assertEquals(listOf(201, 409), results.map { it.response.status }.sorted())
        assertEquals(1L, count("reservations"))
        assertEquals(1L, count("reservation_audit"))
        assertEquals(1L, visibleCustomers())
        val established = customers.findById(salon.customer).orElseThrow()
        assertEquals(1L, established.version)
        if (results[0].response.status == 201) {
            assertEquals("Jan", established.firstName)
            assertEquals("CUSTOMER_PHONE_ALREADY_EXISTS", tree(results[1])["code"].asString())
        } else {
            assertEquals("Ewa", established.firstName)
            assertEquals("CUSTOMER_PROFILE_CHANGED", tree(results[0])["code"].asString())
        }
    }

    @Test
    fun `booking failure rolls back new staff identity and leaves existing pending profile untouched`() {
        block()
        val absentPhone = "+48500888777"
        val fresh = manual(manualBody() + mapOf("customer" to newSelection(absentPhone)))
        assertEquals(409, fresh.response.status, fresh.response.contentAsString)
        assertEquals(1L, count("company_customers"))
        assertNull(customers.findByCompanyIdAndPhoneNumber(salon.company, absentPhone))

        shift()
        val pending = book()
        assertEquals(409, pending.response.status, pending.response.contentAsString)
        val untouched = customers.findById(salon.customer).orElseThrow()
        assertNull(untouched.firstBookingAt)
        assertNull(untouched.firstName)
        assertEquals(0L, untouched.version)
        assertEquals(0L, count("reservations"))
        assertEquals(0L, count("reservation_audit"))
    }

    @Test
    fun `simultaneous profile edits with the same revision commit one complete edit`() {
        assertEquals(201, manual().response.status)
        val observed = customers.findById(salon.customer).orElseThrow().version
        val staff = staffCookie()
        fun update(name: String): MvcResult =
            mvc.perform(
                    put("/api/customers/${salon.customer}/profile")
                        .with(csrf())
                        .cookie(staff)
                        .header("If-Match", "\"$observed\"")
                        .contentType("application/json")
                        .content(json.writeValueAsString(mapOf("firstName" to name, "lastName" to name))),
                )
                .andReturn()
        val results = concurrent({ update("Anna") }, { update("Ewa") })
        assertEquals(listOf(204, 409), results.map { it.response.status }.sorted())
        assertEquals("CONCURRENT_MODIFICATION", tree(results.single { it.response.status == 409 })["code"].asString())
        val actual = customers.findById(salon.customer).orElseThrow()
        assertEquals(observed + 1, actual.version)
        assertEquals(actual.firstName, actual.lastName)
        assertTrue(actual.firstName in listOf("Anna", "Ewa"))
        assertEquals(salon.customer, repository.findAll().single().customerId)
    }

    private fun concurrent(vararg requests: () -> MvcResult): List<MvcResult> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(requests.size)
        try {
            val tasks = requests.map { request ->
                pool.submit<MvcResult> {
                    check(start.await(10, TimeUnit.SECONDS))
                    request()
                }
            }
            start.countDown()
            return tasks.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
