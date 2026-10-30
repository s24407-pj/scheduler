package pl.kacosmetology.scheduler.reservation

import io.mockk.verify
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject

/** Customer submissions enforce current scheduling rules without approval, holds or confirmation SMS. */
class CustomerReservationFlowIntegrationTest : ReservationContractTestSupport() {
    @Test
    fun `successful booking creates BOOKED and salon profile with no notification`() {
        shift()
        val created = book()
        assertEquals(201, created.response.status, created.response.contentAsString)
        assertEquals("BOOKED", tree(created)["status"].stringValue())
        assertEquals(1L, count("company_customers"))
        assertEquals(1L, count("reservation_audit"))
        assertEquals(0L, count("notification_deliveries"))
        verify(exactly = 0) { sms.deliverMessage(any(), any()) }
    }

    @Test
    fun `first booking requires names and failed submission creates no salon profile`() {
        shift()
        assertEquals(400, book(customerBody() - "firstVisitDetails").response.status)
        assertEquals(0L, visibleCustomers())
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `existing salon profile cannot be renamed by a booking request`() {
        shift()
        profile()
        assertEquals(
            409,
            book(
                    customerBody() +
                        mapOf(
                            "firstVisitDetails" to
                                mapOf(
                                    "firstName" to "Hijack",
                                    "lastName" to "Changed",
                                    "contactEmail" to "wrong@example.test",
                                ),
                        ),
                )
                .response
                .status,
        )
        assertEquals("Jan", jdbc.queryForObject<String>("SELECT first_name FROM company_customers"))
        assertNull(jdbc.queryForObject<String>("SELECT contact_email FROM company_customers"))
    }

    @Test
    fun `missing schedule off grid outside shift and employee block each reject booking`() {
        assertEquals(409, book().response.status)
        shift()
        assertEquals(409, book(customerBody(future.plusMinutes(7))).response.status)
        assertEquals(409, book(customerBody(future.withHour(20))).response.status)
        block()
        assertEquals(409, book().response.status)
        assertEquals(0L, visibleCustomers())
    }

    @Test
    fun `foreign employee and disabled or unqualified employee cannot be booked`() {
        shift()
        val foreign = fixtures.salon()
        assertEquals(404, book(customerBody(employee = foreign.employee)).response.status)
        jdbc.update("UPDATE company_employees SET enabled=false WHERE id=?", salon.staff)
        assertEquals(409, book().response.status)
        jdbc.update(
            "UPDATE company_employees SET enabled=true,eligibility_mode='SPECIFIC_SERVICES' WHERE id=?",
            salon.staff,
        )
        assertEquals(409, book().response.status)
    }

    @Test
    fun `zero notice requires strict future while positive notice boundary is inclusive`() {
        shift()
        setLocalNow(future)
        assertEquals(409, book().response.status)
        setLocalNow(localNow)
        jdbc.update("UPDATE companies SET min_booking_advance_minutes=60 WHERE id=?", salon.company)
        assertEquals(201, book().response.status)
    }

    @Test
    fun `final 90th calendar date is bookable and following date is rejected`() {
        val last = localNow.toLocalDate().plusDays(90).atTime(19, 30)
        shift(date = last)
        assertEquals(
            java.time.LocalTime.of(20, 0),
            slotPolicy.effectiveShift(salon.company, salon.employee, last.toLocalDate())!!.endTime,
        )
        val created = book(customerBody(last))
        assertEquals(201, created.response.status, created.response.contentAsString)
        shift(date = last.plusDays(1))
        assertEquals(409, book(customerBody(last.plusDays(1))).response.status)
    }

    @Test
    fun `customer cancels only own future booking and cannot self reschedule`() {
        val id = reservation()
        assertEquals(
            403,
            edit(id, mapOf("startTime" to future.plusHours(1).toString()), cookie = customerCookie()).response.status,
        )
        assertEquals(404, action(id, "cancel", cookie = customerCookie(newCustomer())).response.status)
        setLocalNow(future)
        assertEquals(409, action(id, "cancel", cookie = customerCookie()).response.status)
        setLocalNow(localNow)
        assertEquals(204, action(id, "cancel", cookie = customerCookie()).response.status)
        assertEquals("CANCELLED", state(id))
    }

    @Test
    fun `same customer may overlap appointments at independent salons`() {
        shift()
        assertEquals(201, book().response.status)
        val other = fixtures.salon()
        fixtures.shift(other, future)
        assertEquals(
            201,
            book(
                    customerBody(employee = other.employee, service = other.service),
                    cookie = customerCookie(other.customer),
                )
                .response
                .status,
        )
        assertEquals(2L, count("reservations"))
    }

    @Test
    fun `new submission key cannot bypass employee overlap with a conflicting booking`() {
        shift()
        val first = book()
        assertEquals(201, first.response.status)
        val duplicate = book(key = UUID.randomUUID().toString())
        assertEquals(409, duplicate.response.status)
        assertEquals(1L, count("reservations"))
    }

    @Test
    fun `atomic any employee allocation books distinct available employees under concurrency`() {
        shift()
        val other = newEmployee().first
        shift(other)
        val cookies = listOf(customerCookie(), customerCookie(newCustomer()))
        val results = race(cookies.indices.map { i -> { book(customerBody(employee = null), cookie = cookies[i]) } })
        assertEquals(
            listOf(201, 201),
            results.map { it.response.status }.sorted(),
            results.joinToString { it.response.contentAsString },
        )
        assertEquals(
            setOf(salon.employee, other),
            results.map { UUID.fromString(tree(it)["employeeId"].asString()) }.toSet(),
        )
        assertEquals(2L, count("reservations"))
    }

    @Test
    fun `any employee load ratio counts completed minutes and never subtracts blocks from shift`() {
        shift(start = "08:00", end = "20:00")
        val other = newEmployee().first
        shift(other, "08:00", "16:00")
        val a = reservation(future.withHour(8), "COMPLETED", newCustomer())
        jdbc.update(
            "UPDATE reservations SET end_time=?,agreed_duration_minutes=120 WHERE id=?",
            future.withHour(10).atZone(pl.kacosmetology.scheduler.config.BUSINESS_ZONE).toOffsetDateTime(),
            a,
        )
        val b = reservation(future.withHour(8), customer = newCustomer(), employee = other)
        jdbc.update(
            "UPDATE reservations SET end_time=?,agreed_duration_minutes=90 WHERE id=?",
            future
                .withHour(9)
                .withMinute(30)
                .atZone(pl.kacosmetology.scheduler.config.BUSINESS_ZONE)
                .toOffsetDateTime(),
            b,
        )
        block(start = future.withHour(16), end = future.withHour(20))
        val created = book(customerBody(employee = null))
        assertEquals(201, created.response.status, created.response.contentAsString)
        assertEquals(salon.employee, UUID.fromString(tree(created)["employeeId"].asString()))
    }

    @Test
    fun `future customer booking cap is atomic across concurrent nonoverlapping requests`() {
        shift()
        profile()
        jdbc.update("UPDATE companies SET max_future_customer_bookings=1 WHERE id=?", salon.company)
        val cookie = customerCookie()
        val results =
            race(
                listOf(
                    { book(customerBody() - "firstVisitDetails", cookie = cookie) },
                    { book(customerBody(future.plusHours(1)) - "firstVisitDetails", cookie = cookie) },
                ),
            )
        assertEquals(listOf(201, 409), results.map { it.response.status }.sorted())
        assertEquals(1L, count("reservations"))
        assertTrue(results.any { it.response.contentAsString.contains("BOOKING_LIMIT_REACHED") })
    }

    @Test
    fun `simultaneous different customers cannot book the same employee interval`() {
        shift()
        val a = customerCookie()
        val b = customerCookie(newCustomer())
        val results = race(listOf({ book(cookie = a) }, { book(cookie = b) }))
        assertEquals(listOf(201, 409), results.map { it.response.status }.sorted())
        assertEquals(1L, count("reservations"))
    }

    @Test
    fun `pre MVC invalid payload remains bound to submission key`() {
        val key = "unknown-field-bound"
        assertEquals(400, book(customerBody() + mapOf("unexpected" to true), key).response.status)
        assertEquals(409, book(customerBody(), key).response.status)
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `policy update committed under company lock is authoritative for a waiting booking`() {
        shift()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val policy = pool.submit {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbc.queryForObject<UUID>(
                        "SELECT id FROM companies WHERE id=? FOR UPDATE",
                        salon.company,
                    )
                    jdbc.update("UPDATE companies SET min_booking_advance_minutes=120 WHERE id=?", salon.company)
                    held.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val request = pool.submit<org.springframework.test.web.servlet.MvcResult> { book() }
            org.junit.jupiter.api.assertThrows<java.util.concurrent.TimeoutException> {
                request.get(200, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            policy.get(10, TimeUnit.SECONDS)
            val result = request.get(15, TimeUnit.SECONDS)
            assertEquals(409, result.response.status, result.response.contentAsString)
            assertEquals("BOOKING_ADVANCE_REQUIRED", tree(result)["code"].stringValue())
            assertEquals(0L, visibleCustomers())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `simultaneous manual first visits at different companies create independent canonical phone identities`() {
        val other = fixtures.salon()
        val a = staffCookie()
        val b = staffCookie(other.staff)
        val results =
            race(
                listOf(
                    { manual(manualBody(phone = "+48500100200"), cookie = a) },
                    {
                        manual(
                            manualBody(employee = other.employee, service = other.service, phone = "+48500100200") +
                                mapOf(
                                    "customer" to
                                        mapOf(
                                            "kind" to "new",
                                            "phoneNumber" to "+48500100200",
                                            "firstName" to "Other",
                                            "lastName" to "Klient",
                                        ),
                                ),
                            cookie = b,
                        )
                    },
                ),
            )
        assertEquals(
            listOf(201, 201),
            results.map { it.response.status }.sorted(),
            results.joinToString { it.response.contentAsString },
        )
        assertEquals(
            2L,
            jdbc.queryForObject<Long>(
                "SELECT count(*) FROM company_customers WHERE phone_number='+48500100200'",
            ),
        )
        assertEquals(
            setOf("Jan", "Other"),
            jdbc
                .queryForList<String>("SELECT first_name FROM company_customers WHERE first_booking_at IS NOT NULL")
                .toSet(),
        )
    }

    @Test
    fun `successful submission replay after completion ignores changed current rules without another allocation`() {
        shift()
        val key = "completed-replay"
        val created = book(key = key)
        assertEquals(201, created.response.status)
        val id = id(created)
        setLocalNow(future)
        assertEquals(204, action(id, "complete").response.status)
        jdbc.update("UPDATE company_employees SET enabled=false WHERE id=?", salon.staff)
        jdbc.update("UPDATE offerings SET active=false WHERE id=?", salon.service)
        val replay = book(key = key)
        assertEquals(200, replay.response.status, replay.response.contentAsString)
        assertEquals(id, id(replay))
        assertEquals("COMPLETED", tree(replay)["status"].stringValue())
        assertEquals(tree(created)["createdAt"], tree(replay)["createdAt"])
        assertEquals(1L, count("reservations"))
        assertEquals(2L, count("reservation_audit"))
        assertEquals(0L, count("notification_deliveries"))
    }

    @Test
    fun `submission locked longer than five seconds returns explicit in progress then succeeds after release`() {
        val key = "locked-submission"
        assertEquals(409, book(key = key).response.status)
        shift()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val lock = pool.submit {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbc.queryForMap("SELECT * FROM booking_submissions WHERE submission_key=? FOR UPDATE", key)
                    held.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val busy = book(key = key)
            assertEquals(409, busy.response.status, busy.response.contentAsString)
            assertEquals("IDEMPOTENCY_IN_PROGRESS", tree(busy)["code"].stringValue())
            assertEquals(0L, count("reservations"))
            release.countDown()
            lock.get(10, TimeUnit.SECONDS)
            assertEquals(201, book(key = key).response.status)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun race(
        calls: List<() -> org.springframework.test.web.servlet.MvcResult>,
    ): List<org.springframework.test.web.servlet.MvcResult> {
        val pool = Executors.newFixedThreadPool(calls.size)
        val ready = CountDownLatch(calls.size)
        val go = CountDownLatch(1)
        try {
            val futures = calls.map { call ->
                pool.submit<org.springframework.test.web.servlet.MvcResult> {
                    ready.countDown()
                    check(go.await(10, TimeUnit.SECONDS))
                    call()
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            go.countDown()
            pool.shutdownNow()
        }
    }
}
