package pl.kacosmetology.scheduler.availability

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import pl.kacosmetology.scheduler.reservation.ReservationContractTestSupport

@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
class PersonalConflictIntegrationTest : ReservationContractTestSupport() {
    @Autowired private lateinit var availability: AvailabilityService
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `customer conflicts use one scalar query for both small and large slot grids`() {
        reservation(employee = newEmployee().first)
        val stats = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        for ((end, expectedSlots) in listOf("13:00" to 2, "20:00" to 16)) {
            shift(start = "12:00", end = end)
            stats.clear()
            val public = availability.getAvailableSlots(salon.employee, salon.service, future.toLocalDate())
            val publicQueries = stats.prepareStatementCount
            val publicEntities = stats.entityLoadCount
            stats.clear()
            val personal =
                availability.getAvailableSlots(salon.employee, salon.service, future.toLocalDate(), salon.customer)
            assertEquals(expectedSlots, personal.size)
            assertEquals(
                publicQueries + 1,
                stats.prepareStatementCount,
                "Exactly one customer query for $expectedSlots slots",
            )
            assertEquals(publicEntities, stats.entityLoadCount, "Personal query must not load reservation entities")
            assertEquals(public, personal.map { it.copy(personalConflict = false) })
            assertEquals(listOf(future.toLocalTime()), personal.filter { it.personalConflict }.map { it.time })
        }
    }

    @Test
    fun `authenticated HTTP slots mark occupied visits across employees and preserve public response`() {
        shift(start = "12:00", end = "16:00")
        val otherEmployee = newEmployee().first
        reservation(employee = otherEmployee)
        reservation(future.plusHours(1), "COMPLETED", employee = otherEmployee)
        reservation(future.plusHours(2), "CANCELLED", employee = otherEmployee)
        reservation(future.plusHours(3), "NO_SHOW", employee = otherEmployee)
        val foreign = fixtures.salon()
        fixtures.reservation(foreign, future.plusHours(2))
        val cookie = customerCookie()
        fun request() =
            get("/api/availability")
                .param("employeeId", salon.employee.toString())
                .param("serviceId", salon.service.toString())
                .param("date", future.toLocalDate().toString())
        val public = mvc.perform(request()).andReturn()
        val personal = mvc.perform(request().cookie(cookie)).andReturn()
        assertEquals(200, public.response.status)
        assertEquals(200, personal.response.status)
        val publicSlots = tree(public).values().toList()
        val personalSlots = tree(personal).values().toList()
        assertEquals(8, personalSlots.size)
        assertEquals(
            listOf(true, false, true, false, false, false, false, false),
            personalSlots.map { it["personalConflict"].asBoolean() },
        )
        assertTrue(publicSlots.none { it["personalConflict"].asBoolean() })
        for (field in listOf("time", "price", "originalPrice")) {
            assertEquals(publicSlots.map { it[field] }, personalSlots.map { it[field] })
        }
    }
}
