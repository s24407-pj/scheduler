package pl.kacosmetology.scheduler.reservation

import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

/** Manual booking, editing and lifecycle writes preserve history and require explicit revisions. */
class ReservationStaffIntegrationTest : ReservationContractTestSupport() {
    @Test
    fun `manual booking bypasses grid schedules notice horizon and discounts at regular price`() {
        jdbc.update(
            "UPDATE companies SET min_booking_advance_minutes=1440,max_booking_advance_days=1,last_minute_discount_percent=50,last_minute_discount_hours=24 WHERE id=?",
            salon.company,
        )
        val created = manual(manualBody(localNow.minusMinutes(5)))
        assertEquals(201, created.response.status, created.response.contentAsString)
        assertEquals(101, tree(created)["price"].intValue())
        assertEquals("BOOKED", tree(created)["status"].stringValue())
        assertEquals(0L, count("notification_deliveries"))
        val far = manual(manualBody(future.plusDays(120)))
        assertEquals(201, far.response.status, far.response.contentAsString)
    }

    @Test
    fun `both staff roles may book with explicit price and duration overrides`() {
        val employee = newEmployee()
        val created =
            manual(manualBody() + mapOf("price" to 77, "durationMinutes" to 45), cookie = staffCookie(employee.second))
        assertEquals(201, created.response.status, created.response.contentAsString)
        assertEquals(77, tree(created)["price"].intValue())
        assertEquals(45, tree(created)["durationMinutes"].intValue())
    }

    @Test
    fun `manual first visit creates only phone account and company profile atomically`() {
        val created = manual(manualBody(phone = "+48500100200"))
        assertEquals(201, created.response.status, created.response.contentAsString)
        assertEquals(2L, count("customer_accounts"))
        assertEquals(1L, count("company_customers"))
        assertEquals(
            400,
            manual(manualBody(future.plusHours(1), phone = "+48500100201") - "customerFirstName").response.status,
        )
        assertEquals(2L, count("customer_accounts"))
        assertEquals(1L, count("company_customers"))
    }

    @Test
    fun `manual posting requires idempotency key and same key replays current row`() {
        val body = manualBody()
        val key = "manual-replay"
        assertEquals(400, postJson("/api/reservations/staff", body, staffCookie()).response.status)
        val first = manual(body, key)
        assertEquals(201, first.response.status)
        assertEquals(200, manual(body, key).response.status)
        assertEquals(409, manual(body + mapOf("price" to 12), key).response.status)
        assertEquals(1L, count("reservations"))
    }

    @Test
    fun `foreign service or employee remains opaque and inactive service is rejected`() {
        val other = fixtures.salon()
        assertEquals(404, manual(manualBody(service = other.service)).response.status)
        assertEquals(404, manual(manualBody(employee = other.employee)).response.status)
        jdbc.update("UPDATE offerings SET active=false WHERE id=?", salon.service)
        assertEquals(409, manual().response.status)
    }

    @Test
    fun `only owner may explicitly override an employee block and block stays stored`() {
        block()
        assertEquals(409, manual().response.status)
        val employee = newEmployee()
        assertEquals(
            403,
            manual(manualBody() + mapOf("overrideEmployeeBlock" to true), cookie = staffCookie(employee.second))
                .response
                .status,
        )
        assertEquals(201, manual(manualBody() + mapOf("overrideEmployeeBlock" to true)).response.status)
        assertEquals(1L, count("schedule_blocks"))
    }

    @Test
    fun `manual booking keeps hard employee and company customer conflicts`() {
        reservation()
        assertEquals(409, manual().response.status)
        val other = newEmployee().first
        assertEquals(409, manual(manualBody(employee = other)).response.status)
        assertEquals(1L, count("reservations"))
    }

    @Test
    fun `manual staff booking is exempt from customer created cap`() {
        jdbc.update("UPDATE companies SET max_future_customer_bookings=1 WHERE id=?", salon.company)
        reservation()
        assertEquals(201, manual(manualBody(future.plusHours(1))).response.status)
    }

    @Test
    fun `customer origin restoration rechecks current schedule while preserving cancelled agreement`() {
        shift()
        val created = book()
        assertEquals(201, created.response.status)
        val id = id(created)
        assertEquals(204, action(id, "cancel-by-staff", body = mapOf("reason" to "Zmiana planu")).response.status)
        jdbc.update("DELETE FROM employee_work_schedules WHERE company_id=?", salon.company)
        assertEquals(409, action(id, "restore").response.status)
        assertEquals("CANCELLED", state(id))
        assertEquals(101, jdbc.queryForObject("SELECT price FROM reservations WHERE id=?", Int::class.java, id))
        shift()
        assertEquals(204, action(id, "restore").response.status)
    }

    @Test
    fun `reducing customer booking cap preserves existing visits and allows staff intervention`() {
        shift()
        assertEquals(201, book().response.status)
        assertEquals(201, book(customerBody(future.plusHours(1))).response.status)
        jdbc.update("UPDATE companies SET max_future_customer_bookings=1 WHERE id=?", salon.company)
        assertEquals(2L, count("reservations"))
        assertEquals(409, book(customerBody(future.plusHours(2))).response.status)
        assertEquals(201, manual(manualBody(future.plusHours(2))).response.status)
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE status='BOOKED'", Int::class.java))
    }

    @Test
    fun `staff cancellation requires private reason and customer explanation is separate`() {
        val id = reservation()
        assertEquals(400, action(id, "cancel-by-staff").response.status)
        assertEquals(
            204,
            action(
                    id,
                    "cancel-by-staff",
                    body = mapOf("reason" to "Wewnętrzny powód", "customerExplanation" to "Przepraszamy"),
                )
                .response
                .status,
        )
        val customer = mvc.perform(get("/api/reservations/me").cookie(customerCookie())).andReturn()
        assertFalse(customer.response.contentAsString.contains("Wewnętrzny powód"))
        assertEquals(
            "Wewnętrzny powód",
            jdbc.queryForObject(
                "SELECT internal_cancellation_reason FROM reservations WHERE id=?",
                String::class.java,
                id,
            ),
        )
    }

    @Test
    fun `staff may cancel completed historical appointment without late limit`() {
        val id = reservation(future.minusYears(2), "COMPLETED")
        assertEquals(204, action(id, "cancel-by-staff", body = mapOf("reason" to "Korekta")).response.status)
        assertEquals("CANCELLED", state(id))
    }

    @Test
    fun `direct edit preserves id creation time and same service commercial agreement`() {
        val id = reservation()
        val created =
            jdbc.queryForObject("SELECT created_at::text FROM reservations WHERE id=?", String::class.java, id)
        jdbc.update(
            "UPDATE offerings SET name='Renamed',price=999,duration_minutes=90,active=false WHERE id=?",
            salon.service,
        )
        val edited = edit(id, mapOf("startTime" to future.plusHours(1).toString()))
        assertEquals(200, edited.response.status, edited.response.contentAsString)
        assertEquals(id, id(edited))
        assertEquals(101, tree(edited)["price"].intValue())
        assertEquals(30, tree(edited)["durationMinutes"].intValue())
        assertEquals("Strzyżenie", tree(edited)["serviceName"].stringValue())
        assertEquals(
            created,
            jdbc.queryForObject("SELECT created_at::text FROM reservations WHERE id=?", String::class.java, id),
        )
        assertEquals(1L, version(id))
    }

    @Test
    fun `service change uses current regular name price and duration and customer cannot change`() {
        val id = reservation()
        val service =
            jdbc.queryForObject(
                "INSERT INTO offerings(company_id,name,duration_minutes,price) VALUES(?,'New service',45,222) RETURNING id",
                UUID::class.java,
                salon.company,
            )!!
        val result = edit(id, mapOf("serviceId" to service))
        assertEquals(200, result.response.status, result.response.contentAsString)
        assertEquals(222, tree(result)["price"].intValue())
        assertEquals(45, tree(result)["durationMinutes"].intValue())
        assertEquals("New service", tree(result)["serviceName"].stringValue())
        assertEquals(400, edit(id, mapOf("customerId" to newCustomer())).response.status)
    }

    @Test
    fun `stale revisions no changes and terminal current end prevent invalid edits`() {
        val id = reservation()
        assertEquals(409, edit(id, mapOf("price" to 80), revision = 99).response.status)
        assertEquals(200, edit(id, emptyMap<String, String>()).response.status)
        assertEquals(0L, version(id))
        assertEquals(1L, count("reservation_audit"))
        setLocalNow(future.plusMinutes(30))
        assertEquals(409, edit(id, mapOf("startTime" to future.plusHours(1).toString())).response.status)
    }

    @Test
    fun `archive preserves database identity and requires unarchive before restore`() {
        shift()
        val id = reservation()
        assertEquals(
            405,
            mvc.perform(delete("/api/reservations/$id").cookie(staffCookie()).with(csrf())).andReturn().response.status,
        )
        assertEquals(409, action(id, "archive").response.status)
        assertEquals(204, action(id, "cancel-by-staff", body = mapOf("reason" to "Archiwum")).response.status)
        val employee = newEmployee()
        assertEquals(403, action(id, "archive", cookie = staffCookie(employee.second)).response.status)
        assertEquals(204, action(id, "archive").response.status)
        assertEquals(1L, count("reservations"))
        assertEquals(409, action(id, "restore").response.status)
        assertEquals(204, action(id, "unarchive").response.status)
        assertEquals(204, action(id, "restore").response.status)
        assertEquals("BOOKED", state(id))
    }

    @Test
    fun `repeated completion has no extra audit and stale command after reversal conflicts`() {
        val id = reservation(localNow.minusMinutes(5))
        assertEquals(204, action(id, "complete", 0).response.status)
        val audits = count("reservation_audit")
        assertEquals(204, action(id, "complete", 0).response.status)
        assertEquals(audits, count("reservation_audit"))
        assertEquals(204, action(id, "no-show").response.status)
        assertEquals(204, action(id, "complete").response.status)
        assertEquals(409, action(id, "complete", 0).response.status)
    }

    @Test
    fun `writes require If Match and outcome explanations are durably audited`() {
        val id = reservation(localNow)
        val missing =
            mvc.perform(patch("/api/reservations/$id/complete").cookie(staffCookie()).with(csrf())).andReturn()
        assertEquals(400, missing.response.status)
        assertEquals(204, action(id, "complete", body = mapOf("reason" to "Zakończono wcześniej")).response.status)
        assertEquals(
            "Zakończono wcześniej",
            jdbc.queryForObject("SELECT outcome_explanation FROM reservations WHERE id=?", String::class.java, id),
        )
        assertEquals(
            "Zakończono wcześniej",
            jdbc.queryForObject(
                "SELECT after_state->>'outcome_explanation' FROM reservation_audit WHERE reservation_id=? ORDER BY id DESC LIMIT 1",
                String::class.java,
                id,
            ),
        )
    }

    @Test
    fun `same textual submission key is independent across customer and staff realms`() {
        shift()
        val key = "shared-key"
        assertEquals(201, book(key = key).response.status)
        assertEquals(201, manual(manualBody(future.plusHours(1)), key).response.status)
        assertEquals(2L, count("reservations"))
        assertEquals(2L, count("booking_submissions"))
    }
}
