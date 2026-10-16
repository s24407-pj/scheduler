package pl.kacosmetology.scheduler.reservation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

/** Lists apply realm/company scope, half-open interval filters and archive privacy. */
class ReservationListingIntegrationTest : ReservationContractTestSupport() {
    @Test
    fun `customer history contains own unarchived visits ordered descending`() {
        val first = reservation(future)
        val second = reservation(future.plusDays(1))
        reservation(future.plusDays(2), customer = newCustomer())
        val hidden = reservation(future.plusDays(3), "CANCELLED")
        jdbc.update("UPDATE reservations SET archived=true WHERE id=?", hidden)
        val response = mvc.perform(get("/api/reservations/me").cookie(customerCookie())).andReturn()
        assertEquals(200, response.response.status)
        assertEquals(listOf(second, first), tree(response).values().map { it["id"].longValue() })
    }

    @Test
    fun `staff realm cannot use customer history endpoint`() {
        assertEquals(403, mvc.perform(get("/api/reservations/me").cookie(staffCookie())).andReturn().response.status)
        assertEquals(401, mvc.perform(get("/api/reservations/me")).andReturn().response.status)
    }

    @Test
    fun `employee calendar includes overlapping range start sorted ascending and excludes archived`() {
        val first = reservation(future.minusMinutes(15))
        val second = reservation(future.plusHours(1))
        val hidden = reservation(future.plusHours(2), "CANCELLED")
        jdbc.update("UPDATE reservations SET archived=true WHERE id=?", hidden)
        val response =
            mvc.perform(
                    get("/api/reservations/employee")
                        .cookie(staffCookie())
                        .param("start", future.toString())
                        .param("end", future.plusHours(3).toString()),
                )
                .andReturn()
        assertEquals(200, response.response.status)
        assertEquals(listOf(first, second), tree(response).values().map { it["id"].longValue() })
    }

    @Test
    fun `customer cannot read staff calendars`() {
        val response =
            mvc.perform(
                    get("/api/reservations/employee")
                        .cookie(customerCookie())
                        .param("start", future.toString())
                        .param("end", future.plusDays(1).toString()),
                )
                .andReturn()
        assertEquals(403, response.response.status)
    }

    @Test
    fun `booking replay display follows current employee and company while preserving accepted service`() {
        shift()
        val key = "display-replay"
        val created = book(key = key)
        assertEquals(201, created.response.status, created.response.contentAsString)
        jdbc.update("UPDATE companies SET name='Changed salon',address='Kraków' WHERE id=?", salon.company)
        jdbc.update("UPDATE company_employees SET first_name='New',last_name='Employee' WHERE id=?", salon.staff)
        jdbc.update(
            "UPDATE offerings SET name='Changed catalog',price=999,duration_minutes=60 WHERE id=?",
            salon.service,
        )
        val replay = book(key = key)
        assertEquals(200, replay.response.status)
        val body = tree(replay)
        assertEquals("Changed salon", body["companyName"].stringValue())
        assertEquals("Kraków", body["companyAddress"].stringValue())
        assertEquals("New Employee", body["employeeName"].stringValue())
        assertEquals("Strzyżenie", body["serviceName"].stringValue())
        assertEquals(101, body["price"].intValue())
        assertEquals(30, body["durationMinutes"].intValue())
        assertEquals(tree(created)["createdAt"], body["createdAt"])
    }
}
