package pl.kacosmetology.scheduler.reservation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException

/** Repository and PostgreSQL exclusion constraints protect both employee and company/customer intervals. */
class ReservationRepositoryTest : ReservationContractTestSupport() {
    @Test
    fun `employee overlap detects containment and boundaries remain half open`() {
        reservation()
        for ((start, end) in
            listOf(
                future to future.plusMinutes(30),
                future.plusMinutes(10) to future.plusMinutes(20),
                future.minusMinutes(5) to future.plusMinutes(5),
                future.plusMinutes(25) to future.plusMinutes(35),
            )) assertTrue(repository.existsOverlapping(salon.company, salon.employee, start, end))
        assertFalse(
            repository.existsOverlapping(salon.company, salon.employee, future.plusMinutes(30), future.plusHours(1)),
        )
        assertFalse(repository.existsOverlapping(salon.company, salon.employee, future.minusMinutes(30), future))
    }

    @Test
    fun `cancelled and no show free interval while completed retains entire interval`() {
        val id = reservation(status = "CANCELLED")
        assertFalse(repository.existsOverlapping(salon.company, salon.employee, future, future.plusMinutes(30)))
        jdbc.update("UPDATE reservations SET status='NO_SHOW' WHERE id=?", id)
        assertFalse(repository.existsOverlapping(salon.company, salon.employee, future, future.plusMinutes(30)))
        jdbc.update("UPDATE reservations SET status='COMPLETED' WHERE id=?", id)
        assertTrue(repository.existsOverlapping(salon.company, salon.employee, future, future.plusMinutes(30)))
    }

    @Test
    fun `customer overlap spans employees but remains independent of other companies`() {
        val other = newEmployee().first
        reservation(employee = other)
        assertTrue(repository.existsCustomerOverlapping(salon.company, salon.customer, future, future.plusMinutes(30)))
        val foreign = fixtures.salon()
        assertFalse(
            repository.existsCustomerOverlapping(foreign.company, salon.customer, future, future.plusMinutes(30)),
        )
        fixtures.reservation(foreign, future)
        assertEquals(2L, count("reservations"))
    }

    @Test
    fun `database rejects employee overlap even when optimistic service checks are bypassed`() {
        reservation()
        val otherCustomer = newCustomer()
        assertThrows<DataIntegrityViolationException> { reservation(customer = otherCustomer) }
        assertEquals(1L, count("reservations"))
    }

    @Test
    fun `database rejects customer overlap across employees even when service checks are bypassed`() {
        reservation()
        val other = newEmployee().first
        assertThrows<DataIntegrityViolationException> { reservation(employee = other) }
        assertEquals(1L, count("reservations"))
    }

    @Test
    fun `historical completed status restoration cannot introduce a conflicting interval`() {
        val old = reservation(status = "NO_SHOW")
        reservation(customer = newCustomer())
        assertThrows<DataIntegrityViolationException> {
            jdbc.update("UPDATE reservations SET status='COMPLETED' WHERE id=?", old)
        }
        assertEquals("NO_SHOW", state(old))
    }

    @Test
    fun `employee schedule is company scoped includes overlapping starts and omits archives`() {
        val id = reservation(future.minusMinutes(15))
        val hidden = reservation(future.plusHours(1), "CANCELLED")
        jdbc.update("UPDATE reservations SET archived=true WHERE id=?", hidden)
        val selected = repository.findEmployeeSchedule(salon.company, salon.employee, future, future.plusHours(2))
        assertEquals(listOf(id), selected.map { it.id })
        val other = fixtures.salon()
        assertTrue(
            repository.findEmployeeSchedule(other.company, salon.employee, future, future.plusHours(2)).isEmpty(),
        )
    }

    @Test
    fun `future count excludes staff source terminal outcomes and ongoing visits`() {
        val customer = reservation()
        val manual = reservation(future.plusHours(1))
        jdbc.update("UPDATE reservations SET creation_source='STAFF' WHERE id=?", manual)
        reservation(localNow.minusMinutes(5))
        reservation(future.plusHours(2), "CANCELLED")
        assertEquals(1L, repository.countFutureCustomerBookings(salon.company, salon.customer, localNow))
        assertEquals(
            customer,
            repository
                .findAllByCompanyIdAndCustomerIdAndArchivedFalseOrderByStartTimeDesc(salon.company, salon.customer)
                .first { it.id == customer }
                .id,
        )
    }
}
