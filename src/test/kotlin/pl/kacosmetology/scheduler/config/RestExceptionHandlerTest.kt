package pl.kacosmetology.scheduler.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.sql.SQLException

class RestExceptionHandlerTest {

    private val handler = RestExceptionHandler()

    @Test
    fun `api exception should use the status and code of its error code`() {
        val response = handler.handleApiException(ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Rezerwacja nie istnieje"))

        assertEquals(404, response.statusCode.value())
        assertEquals("RESERVATION_NOT_FOUND", response.body!!.properties!!["code"])
        assertEquals("Rezerwacja nie istnieje", response.body!!.detail)
    }

    @Test
    fun `exclusion constraint violation should be reported as an unavailable slot`() {
        val ex = DataIntegrityViolationException("overlap", SQLException("conflicting key value", "23P01"))

        val response = handler.handleDataIntegrityViolation(ex)

        assertEquals(409, response.statusCode.value())
        assertEquals("SLOT_UNAVAILABLE", response.body!!.properties!!["code"])
    }

    @Test
    fun `other integrity violations should be reported as a data conflict`() {
        val ex = DataIntegrityViolationException("duplicate", SQLException("duplicate key", "23505"))

        val response = handler.handleDataIntegrityViolation(ex)

        assertEquals(409, response.statusCode.value())
        assertEquals("DATA_CONFLICT", response.body!!.properties!!["code"])
    }

    @Test
    fun `optimistic lock failure should be reported as a concurrent modification`() {
        val ex = ObjectOptimisticLockingFailureException("Reservation", 1L)

        val response = handler.handleOptimisticLockingFailure(ex)

        assertEquals(409, response.statusCode.value())
        assertEquals("CONCURRENT_MODIFICATION", response.body!!.properties!!["code"])
    }
}
