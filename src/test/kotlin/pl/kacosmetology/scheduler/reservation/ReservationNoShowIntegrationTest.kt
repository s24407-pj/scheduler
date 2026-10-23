package pl.kacosmetology.scheduler.reservation

import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch

/** No-show events use Warsaw appointment dates and independent manual/automatic block provenance. */
class ReservationNoShowIntegrationTest : ReservationContractTestSupport() {
    private fun customerStatus() =
        mvc.perform(get("/api/customers/${salon.customer}").cookie(staffCookie())).andReturn()

    private fun ownerBlock(operation: String) =
        mvc.perform(patch("/api/customers/${salon.customer}/$operation").cookie(staffCookie()).with(csrf())).andReturn()

    private fun flag(id: UUID, column: String) =
        jdbc.queryForObject(
            "SELECT $column FROM company_customer_blocks WHERE company_id=? AND customer_id=?",
            Boolean::class.java,
            salon.company,
            id,
        )!!

    @Test
    fun `employee no show deadline and correction are inclusive at 24 elapsed hours owner remains unlimited`() {
        val employee = newEmployee()
        val cookie = staffCookie(employee.second)
        val id = reservation(localNow.minusHours(24).minusMinutes(30), "COMPLETED")
        assertEquals(204, action(id, "no-show", cookie = cookie).response.status)
        assertEquals(204, action(id, "complete", cookie = cookie).response.status)
        setNow(initialNow.plusNanos(1))
        assertEquals(409, action(id, "no-show", cookie = cookie).response.status)
        assertEquals(204, action(id, "no-show").response.status)
        assertEquals(409, action(id, "complete", cookie = cookie).response.status)
        assertEquals(204, action(id, "complete").response.status)
    }

    @Test
    fun `no show begins at scheduled start and customer or foreign staff cannot act`() {
        val id = reservation()
        assertEquals(409, action(id, "no-show").response.status)
        assertEquals(403, action(id, "no-show", cookie = customerCookie()).response.status)
        val foreign = fixtures.salon()
        assertEquals(404, action(id, "no-show", cookie = staffCookie(foreign.staff)).response.status)
        setLocalNow(future)
        assertEquals(204, action(id, "no-show").response.status)
    }

    @Test
    fun `completed original occupied interval stays protected against overlapping manual booking`() {
        val id = reservation(localNow.minusMinutes(5))
        assertEquals(204, action(id, "complete").response.status)
        assertEquals(409, manual(manualBody(localNow)).response.status)
        assertEquals(409, edit(id, mapOf("durationMinutes" to 1)).response.status)
        assertEquals(
            30,
            jdbc.queryForObject("SELECT agreed_duration_minutes FROM reservations WHERE id=?", Int::class.java, id),
        )
    }

    @Test
    fun `repeated no show is side effect free and old action conflicts after correction`() {
        val id = reservation(localNow.minusMinutes(5))
        assertEquals(204, action(id, "no-show", 0).response.status)
        val audits = count("reservation_audit")
        assertEquals(204, action(id, "no-show", 0).response.status)
        assertEquals(1L, count("no_show_penalties"))
        assertEquals(audits, count("reservation_audit"))
        assertEquals(204, action(id, "complete").response.status)
        assertEquals(409, action(id, "no-show", 0).response.status)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM no_show_penalties WHERE active", Int::class.java))
    }

    @Test
    fun `correcting counted no show removes automatic block below threshold`() {
        jdbc.update("UPDATE companies SET max_no_shows=1 WHERE id=?", salon.company)
        val id = reservation(localNow.minusHours(1))
        assertEquals(204, action(id, "no-show").response.status)
        assertTrue(flag(salon.customer, "automatic_blocked"))
        assertTrue(flag(salon.customer, "blocked"))
        assertEquals(204, action(id, "complete").response.status)
        assertFalse(flag(salon.customer, "automatic_blocked"))
        assertFalse(flag(salon.customer, "blocked"))
        assertEquals(0, tree(customerStatus())["noShowCount"].intValue())
    }

    @Test
    fun `correcting no show never removes manual block or affects customer authentication`() {
        jdbc.update("UPDATE companies SET max_no_shows=1 WHERE id=?", salon.company)
        val id = reservation(localNow.minusHours(1))
        assertEquals(204, action(id, "no-show").response.status)
        assertEquals(204, ownerBlock("block").response.status)
        assertEquals(204, action(id, "cancel-by-staff", body = mapOf("reason" to "Błędna nieobecność")).response.status)
        assertTrue(flag(salon.customer, "manual_blocked"))
        assertTrue(flag(salon.customer, "blocked"))
        assertEquals(200, mvc.perform(get("/api/auth/me").cookie(customerCookie())).andReturn().response.status)
        assertEquals(0, tree(customerStatus())["noShowCount"].intValue())
    }

    @Test
    fun `owner unblock preserves count and next counted no show blocks again`() {
        jdbc.update("UPDATE companies SET max_no_shows=1 WHERE id=?", salon.company)
        val first = reservation(localNow.minusHours(3))
        assertEquals(204, action(first, "no-show").response.status)
        assertEquals(204, ownerBlock("unblock").response.status)
        assertFalse(flag(salon.customer, "blocked"))
        assertEquals(1, tree(customerStatus())["noShowCount"].intValue())
        val next = reservation(localNow.minusHours(1))
        assertEquals(204, action(next, "no-show").response.status)
        assertTrue(flag(salon.customer, "blocked"))
        assertEquals(2, tree(customerStatus())["noShowCount"].intValue())
    }

    @Test
    fun `rolling calendar year includes anniversary date and excludes preceding date`() {
        val boundary = reservation(localNow.minusYears(1).withHour(9))
        val outside = reservation(localNow.minusYears(1).minusDays(1).withHour(9))
        assertEquals(204, action(boundary, "no-show").response.status)
        assertEquals(204, action(outside, "no-show").response.status)
        assertEquals(2L, count("no_show_penalties"))
        assertEquals(1, tree(customerStatus())["noShowCount"].intValue())
    }

    @Test
    fun `aging out decreases current count but never automatically clears persisted block`() {
        jdbc.update("UPDATE companies SET max_no_shows=1 WHERE id=?", salon.company)
        val boundary = reservation(localNow.minusYears(1).withHour(9))
        assertEquals(204, action(boundary, "no-show").response.status)
        assertTrue(flag(salon.customer, "blocked"))
        setLocalNow(localNow.plusDays(1))
        val status = tree(customerStatus())
        assertEquals(0, status["noShowCount"].intValue())
        assertTrue(status["blocked"].booleanValue())
    }

    @Test
    fun `zero threshold disables automatic blocking and changes never retroactively alter blocks`() {
        jdbc.update("UPDATE companies SET max_no_shows=0 WHERE id=?", salon.company)
        val id = reservation(localNow.minusHours(3))
        assertEquals(204, action(id, "no-show").response.status)
        assertFalse(flag(salon.customer, "blocked"))
        jdbc.update("UPDATE companies SET max_no_shows=1 WHERE id=?", salon.company)
        assertFalse(tree(customerStatus())["blocked"].booleanValue())
        val next = reservation(localNow.minusHours(1))
        assertEquals(204, action(next, "no-show").response.status)
        assertTrue(flag(salon.customer, "blocked"))
        jdbc.update("UPDATE companies SET max_no_shows=0 WHERE id=?", salon.company)
        assertTrue(tree(customerStatus())["blocked"].booleanValue())
    }

    @Test
    fun `block cancels only future booked visits preserves ongoing and is independent of other salon`() {
        val ongoing = reservation(localNow.minusMinutes(5))
        val futureId = reservation()
        val other = fixtures.salon()
        val independent = fixtures.reservation(other, future, customer = salon.customer)
        assertEquals(204, ownerBlock("block").response.status)
        assertEquals("BOOKED", state(ongoing))
        assertEquals("CANCELLED", state(futureId))
        assertEquals("BOOKED", state(independent))
        assertEquals(409, manual(manualBody(future.plusHours(1))).response.status)
        assertEquals(200, edit(ongoing, mapOf("price" to 88)).response.status)
        assertEquals(409, edit(ongoing, mapOf("startTime" to future.plusHours(2).toString())).response.status)
        assertEquals(204, ownerBlock("unblock").response.status)
        assertEquals("CANCELLED", state(futureId))
    }

    @Test
    fun `ongoing no show reopen applies current hard conflicts and removes penalty only on success`() {
        shift()
        val id = reservation(localNow)
        assertEquals(204, action(id, "no-show").response.status)
        val replacement = reservation(localNow, customer = newCustomer())
        assertEquals(409, action(id, "restore").response.status)
        assertEquals("NO_SHOW", state(id))
        assertEquals("BOOKED", state(replacement))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM no_show_penalties WHERE active", Int::class.java))
        assertEquals(
            204,
            action(replacement, "cancel-by-staff", body = mapOf("reason" to "Korekta terminu")).response.status,
        )
        assertEquals(204, action(id, "restore").response.status)
        assertEquals("BOOKED", state(id))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM no_show_penalties WHERE active", Int::class.java))
    }

    @Test
    fun `elapsed employee deadline across autumn DST is not a wall clock day`() {
        val employee = newEmployee()
        val cookie = staffCookie(employee.second)
        val start = java.time.LocalDateTime.parse("2030-10-26T12:00:00")
        val id = reservation(start, "COMPLETED")
        setLocalNow(java.time.LocalDateTime.parse("2030-10-27T11:30:00"))
        assertEquals(204, action(id, "no-show", cookie = cookie).response.status)
        setLocalNow(java.time.LocalDateTime.parse("2030-10-27T12:00:00"))
        assertEquals(409, action(id, "complete", cookie = cookie).response.status)
        assertEquals(204, action(id, "complete").response.status)
    }

    @Test
    fun `no show to completed correction checks both replacement bookings and current blocks`() {
        val id = reservation(localNow)
        assertEquals(204, action(id, "no-show").response.status)
        val replacement = reservation(localNow, customer = newCustomer())
        assertEquals(409, action(id, "complete").response.status)
        assertEquals(204, action(replacement, "cancel-by-staff", body = mapOf("reason" to "Korekta")).response.status)
        block(start = localNow, end = localNow.plusMinutes(30))
        assertEquals(409, action(id, "complete").response.status)
        assertEquals("NO_SHOW", state(id))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM no_show_penalties WHERE active", Int::class.java))
    }

    @Test
    fun `recount after corrected and moved visit uses new appointment date`() {
        shift()
        shift(date = future.plusDays(1))
        val id = reservation()
        setLocalNow(future)
        assertEquals(204, action(id, "no-show").response.status)
        assertEquals(204, action(id, "restore").response.status)
        assertEquals(200, edit(id, mapOf("startTime" to future.plusDays(1).toString())).response.status)
        setLocalNow(future.plusDays(1))
        assertEquals(204, action(id, "no-show").response.status)
        assertEquals(
            future.plusDays(1).toLocalDate(),
            jdbc.queryForObject(
                "SELECT appointment_date FROM no_show_penalties WHERE reservation_id=?",
                java.time.LocalDate::class.java,
                id,
            ),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM no_show_penalties WHERE active", Int::class.java))
    }

    @Test
    fun `uncounted ancient no show cannot revoke owner reprieve`() {
        jdbc.update("UPDATE companies SET max_no_shows=1 WHERE id=?", salon.company)
        val counted = reservation(localNow.minusHours(3))
        assertEquals(204, action(counted, "no-show").response.status)
        assertEquals(204, ownerBlock("unblock").response.status)
        val ancient = reservation(localNow.minusYears(2), "COMPLETED")
        assertEquals(204, action(ancient, "no-show").response.status)
        assertFalse(flag(salon.customer, "blocked"))
        assertEquals(1, tree(customerStatus())["noShowCount"].intValue())
        val next = reservation(localNow.minusHours(1))
        assertEquals(204, action(next, "no-show").response.status)
        assertTrue(flag(salon.customer, "blocked"))
    }

    @Test
    fun `employees cannot manually block or unblock customers`() {
        profile()
        val employee = newEmployee()
        for (operation in listOf("block", "unblock")) assertEquals(
            403,
            mvc.perform(
                    patch("/api/customers/${salon.customer}/$operation")
                        .cookie(staffCookie(employee.second))
                        .with(csrf()),
                )
                .andReturn()
                .response
                .status,
        )
    }

    @Test
    fun `simultaneous outcome changes preserve all penalties and automatic threshold blocking`() {
        val ids = (1..3).map { reservation(localNow.minusHours(it.toLong())) }
        val cookie = staffCookie()
        val pool = Executors.newFixedThreadPool(3)
        val go = CountDownLatch(1)
        try {
            val results = ids.map { id ->
                pool.submit<Int> {
                    check(go.await(10, TimeUnit.SECONDS))
                    action(id, "no-show", 0, cookie = cookie).response.status
                }
            }
            go.countDown()
            assertEquals(listOf(204, 204, 204), results.map { it.get(20, TimeUnit.SECONDS) })
            assertEquals(3L, count("no_show_penalties"))
            assertEquals(3, tree(customerStatus())["noShowCount"].intValue())
            assertTrue(flag(salon.customer, "blocked"))
        } finally {
            go.countDown()
            pool.shutdownNow()
        }
    }
}
