package pl.kacosmetology.scheduler.reservation

import java.util.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import pl.kacosmetology.scheduler.testId

/** Both company staff roles read coworkers' calendars using company-local customer presentation. */
class ReservationDashboardIntegrationTest : ReservationContractTestSupport() {
    private fun calendar(
        employee: UUID = salon.employee,
        cookie: jakarta.servlet.http.Cookie = staffCookie(),
        start: String = future.toString(),
        end: String = future.plusDays(1).toString(),
    ) =
        mvc.perform(
                get("/api/reservations")
                    .cookie(cookie)
                    .param("employeeId", employee.toString())
                    .param("start", start)
                    .param("end", end),
            )
            .andReturn()

    @Test
    fun `owner and employee may read any company employee booking with local customer names`() {
        val id = reservation()
        jdbc.update(
            "UPDATE reservations SET internal_notes='Notatka tylko dla personelu' WHERE id=?",
            id,
        )
        jdbc.update(
            "UPDATE company_customers SET first_name='Company',last_name='Customer' WHERE company_id=? AND user_id=?",
            salon.company,
            salon.customer,
        )
        val coworker = newEmployee()
        for (cookie in listOf(staffCookie(), staffCookie(coworker.second))) {
            val response = calendar(cookie = cookie)
            assertEquals(200, response.response.status, response.response.contentAsString)
            assertEquals(id, UUID.fromString(tree(response)[0]["id"].asString()))
            assertEquals("Company", tree(response)[0]["customerFirstName"].stringValue())
            assertEquals("Customer", tree(response)[0]["customerLastName"].stringValue())
            assertEquals("Notatka tylko dla personelu", tree(response)[0]["internalNotes"].stringValue())
        }
    }

    @Test
    fun `calendar ranges include overlapping starts but exclude visits beginning at end`() {
        val overlaps = reservation(future.minusMinutes(15))
        reservation(future.plusHours(1))
        val response = calendar(end = future.plusHours(1).toString())
        assertEquals(200, response.response.status)
        assertEquals(listOf(overlaps), tree(response).values().map { UUID.fromString(it["id"].asString()) })
    }

    @Test
    fun `malformed offset and Z query date times are invalid input`() {
        for (value in
            listOf(
                "not-a-date",
                future.toString() + "Z",
                future.toString() + "+01:00",
                future.toString() + "+02:00",
            )) assertEquals(400, calendar(start = value).response.status)
    }

    @Test
    fun `foreign and absent employee calendars are opaque missing resources`() {
        val foreign = fixtures.salon()
        assertEquals(404, calendar(employee = foreign.employee).response.status)
        assertEquals(404, calendar(employee = testId(Long.MAX_VALUE)).response.status)
    }

    @Test
    fun `dashboard is unavailable without staff authentication`() {
        assertEquals(403, calendar(cookie = customerCookie()).response.status)
        assertEquals(
            401,
            mvc.perform(
                    get("/api/reservations")
                        .param("employeeId", salon.employee.toString())
                        .param("start", future.toString())
                        .param("end", future.plusDays(1).toString()),
                )
                .andReturn()
                .response
                .status,
        )
    }
}
