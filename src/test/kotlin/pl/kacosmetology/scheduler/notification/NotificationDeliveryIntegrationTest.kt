package pl.kacosmetology.scheduler.notification

import com.ninjasquad.springmockk.MockkBean
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.OtpStore
import pl.kacosmetology.scheduler.auth.RateLimitExceededException
import pl.kacosmetology.scheduler.auth.sms.DailySmsBudget
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE
import pl.kacosmetology.scheduler.reservation.CustomerBlockedBookingsEvent
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationChangedEvent
import pl.kacosmetology.scheduler.reservation.ReservationCreatedEvent
import pl.kacosmetology.scheduler.testId
import software.amazon.awssdk.services.s3.S3Client

/** End-to-end transaction, delivery-state and reminder-clock evidence against PostgreSQL and Redis. */
@SpringBootTest(properties = ["scheduler.jobs.enabled=false"])
@Import(TestcontainersConfiguration::class, NotificationDeliveryIntegrationTest.TestTime::class)
class NotificationDeliveryIntegrationTest {
    @Autowired private lateinit var service: NotificationService
    @Autowired private lateinit var repository: NotificationDeliveryRepository
    @Autowired private lateinit var details: NotificationDetails
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var source: DataSource
    @Autowired private lateinit var clock: NotificationClock
    @Autowired private lateinit var manager: PlatformTransactionManager
    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var redis: StringRedisTemplate
    @MockkBean private lateinit var sender: SmsSender
    @MockkBean private lateinit var s3Client: S3Client
    private val nine = Instant.parse("2027-06-01T07:00:00Z")
    private var companyId = testId(0)
    private var customerId = testId(0)
    private var offeringId = testId(0)
    private var employeeId = testId(90001)
    private lateinit var transactions: TransactionTemplate

    @BeforeEach
    fun reset() {
        clock.set(nine)
        transactions = TransactionTemplate(manager)
        jdbc.execute("TRUNCATE companies,customer_accounts RESTART IDENTITY CASCADE")
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        service.recoverAfterRestart()
        clearMocks(sender)
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.ACCEPTED
        companyId =
            jdbc.queryForObject("INSERT INTO companies(name) VALUES ('Local Salon') RETURNING id", UUID::class.java)!!
        customerId =
            jdbc.queryForObject(
                "INSERT INTO customer_accounts(phone_number) VALUES ('+48880000001') RETURNING id",
                UUID::class.java,
            )!!
        offeringId =
            jdbc.queryForObject(
                "INSERT INTO offerings(company_id,name,duration_minutes,price) VALUES (?,'Current catalog',30,100) RETURNING id",
                UUID::class.java,
                companyId,
            )!!
        jdbc.update(
            "INSERT INTO company_employees(company_id,user_id,role,first_name,last_name,login_email) VALUES (?,?,'EMPLOYEE','Company','Employee','employee@local.test')",
            companyId,
            employeeId,
        )
    }

    @Test
    fun `rollback sends nothing while customer booking and default staff optout emit no confirmation`() {
        val id = booking(nine.plusSeconds(3600))
        assertThrows<IllegalStateException> {
            transactions.executeWithoutResult {
                events.publishEvent(ReservationChangedEvent(id, "CREATE", true))
                error("rollback")
            }
        }
        assertEquals(0L, count())
        transactions.executeWithoutResult {
            events.publishEvent(
                ReservationCreatedEvent(
                    Reservation(
                        id = id,
                        companyId = companyId,
                        customerId = customerId,
                        employeeId = employeeId,
                        serviceId = offeringId,
                        price = 100,
                        startTime = LocalDateTime.ofInstant(nine.plusSeconds(3600), BUSINESS_ZONE),
                        endTime = LocalDateTime.ofInstant(nine.plusSeconds(5400), BUSINESS_ZONE),
                    ),
                ),
            )
        }
        changed(id, "CREATE", false)
        assertEquals(0L, count())
        verify(exactly = 0) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `provider sees committed SENDING and accepted delivery never sends twice`() {
        val id = booking(nine.plusSeconds(3600))
        val text = slot<String>()
        every { sender.deliverMessage("+48880000001", capture(text)) } answers
            {
                source.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT state,attempts FROM notification_deliveries").use { result ->
                            assertTrue(result.next())
                            assertEquals("SENDING", result.getString(1))
                            assertEquals(1, result.getInt(2))
                        }
                    }
                }
                SmsDeliveryResult.ACCEPTED
            }
        changed(id)
        assertEquals("ACCEPTED", state())
        assertTrue(text.captured.contains("Accepted name"))
        assertTrue(text.captured.contains("Company Employee"))
        assertFalse(text.captured.contains("Current catalog"))
        service.dispatchDue()
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `definite temporary failure retries but ambiguous and unknown provider failure do not`() {
        val id = booking(nine.plusSeconds(3600))
        every { sender.deliverMessage(any(), any()) } returnsMany
            listOf(SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE, SmsDeliveryResult.ACCEPTED)
        changed(id)
        assertEquals("TEMPORARY_FAILURE", state())
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        assertEquals("ACCEPTED", state())
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
        edit(id)
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.AMBIGUOUS
        changed(id, "EDIT")
        assertEquals("AMBIGUOUS", state())
        clock.set(nine.plusSeconds(90))
        service.dispatchDue()
        verify(exactly = 3) { sender.deliverMessage(any(), any()) }
        edit(id)
        every { sender.deliverMessage(any(), any()) } throws IllegalStateException("timeout")
        changed(id, "EDIT")
        assertEquals("AMBIGUOUS", state())
        clock.set(nine.plusSeconds(120))
        service.dispatchDue()
        verify(exactly = 4) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `permanent provider rejection discards notification without retry`() {
        val id = booking(nine.plusSeconds(3600))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.PERMANENT_REJECTION
        changed(id)
        assertEquals("DISCARDED", state())
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `duplicate application event preserves original pending delivery without additional side effects`() {
        val id = booking(nine.plusSeconds(3600))
        every { sender.deliverMessage(any(), any()) } returnsMany
            listOf(SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE, SmsDeliveryResult.ACCEPTED)
        changed(id)
        changed(id)
        assertEquals(1L, count())
        assertEquals("TEMPORARY_FAILURE", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        assertEquals("ACCEPTED", state())
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `future message retry stops at exact start and ongoing cancellation is opt in only once`() {
        val id = booking(nine.plusSeconds(60))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        changed(id)
        clock.set(nine.plusSeconds(60))
        service.dispatchDue()
        assertEquals("DISCARDED", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        val ongoing = booking(nine.minusSeconds(60), "CANCELLED")
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.ACCEPTED
        changed(ongoing, "CANCEL")
        changed(ongoing, "CANCEL")
        assertEquals("ACCEPTED", state("CANCEL"))
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `restart marks in flight ambiguous and never blindly retries`() {
        val id = booking(nine.plusSeconds(3600))
        queue(id)
        jdbc.update("UPDATE notification_deliveries SET state='SENDING',attempts=1")
        service.recoverAfterRestart()
        service.dispatchDue()
        assertEquals("AMBIGUOUS", state())
        verify(exactly = 0) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `actual edit opting out invalidates obsolete confirmation and reschedule uses new deadline`() {
        val id = booking(nine.plusSeconds(3600))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        changed(id)
        edit(id, nine.plusSeconds(7200))
        changed(id, "EDIT", false)
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        assertEquals("DISCARDED", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        edit(id, nine.plusSeconds(10800))
        changed(id, "EDIT", true)
        assertEquals(
            Timestamp.from(nine.plusSeconds(10800)),
            jdbc.queryForObject(
                "SELECT deadline FROM notification_deliveries ORDER BY id DESC LIMIT 1",
                Timestamp::class.java,
            ),
        )
    }

    @Test
    fun `ongoing appointment gets one attempt and elapsed cancellation gets none`() {
        val id = booking(nine.minusSeconds(60))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        changed(id)
        assertEquals("DISCARDED", state())
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        edit(id)
        jdbc.update("UPDATE reservations SET status='CANCELLED',end_time=? WHERE id=?", Timestamp.from(nine), id)
        changed(id, "CANCEL", true)
        assertEquals(1L, count())
    }

    @Test
    fun `block summary filters restored or elapsed bookings and retries while any cancelled future remains`() {
        val first = booking(nine.plusSeconds(3600), "CANCELLED")
        val second = booking(nine.plusSeconds(7200), "CANCELLED")
        transactions.executeWithoutResult {
            repository.insertSummary(companyId, customerId, listOf(first, second), clock.instant())
        }
        jdbc.update("UPDATE reservations SET status='BOOKED' WHERE id=?", first)
        val text = slot<String>()
        every { sender.deliverMessage(any(), capture(text)) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        service.dispatchDue()
        assertTrue(text.captured.contains("11:00"))
        assertFalse(text.captured.contains("10:00"))
        assertEquals("TEMPORARY_FAILURE", state())
        jdbc.update("UPDATE reservations SET status='BOOKED' WHERE id=?", second)
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        assertEquals("DISCARDED", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `mandatory block event queues and sends one summary without optional staff flag`() {
        val first = booking(nine.plusSeconds(3600), "CANCELLED")
        val second = booking(nine.plusSeconds(7200), "CANCELLED")
        transactions.executeWithoutResult {
            events.publishEvent(CustomerBlockedBookingsEvent(companyId, customerId, listOf(first, second)))
        }
        assertEquals(1L, count())
        assertEquals("ACCEPTED", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `reminder previous day run has same date identity and allows new appointment date`() {
        val id = booking(nine.plusSeconds(86400 + 3600))
        service.startReminderRun()
        assertEquals("ACCEPTED", state())
        edit(id, nine.plusSeconds(86400 + 7200))
        changed(id, "EDIT", false)
        service.startReminderRun()
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        edit(id, nine.plusSeconds(2 * 86400 + 3600))
        clock.set(nine.plusSeconds(86400))
        service.startReminderRun()
        assertEquals(2L, count())
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `created or moved after nine cutoff gets no reminder while non-time edit remains eligible`() {
        clock.set(nine.plusSeconds(60))
        booking(nine.plusSeconds(86400 + 3600), created = nine.plusSeconds(1))
        val moved = booking(nine.plusSeconds(2 * 86400 + 3600))
        edit(moved, nine.plusSeconds(86400 + 7200))
        val movedSameDay = booking(nine.plusSeconds(86400 + 10800))
        edit(movedSameDay, nine.plusSeconds(86400 + 14400))
        val sameDay = booking(nine.plusSeconds(86400 + 18000))
        edit(sameDay)
        service.startReminderRun()
        assertEquals(1L, count())
        assertEquals(
            sameDay,
            jdbc.queryForObject("SELECT reservation_id FROM notification_deliveries", UUID::class.java),
        )
    }

    @Test
    fun `temporary reminder retry uses active run only until nine oh five`() {
        booking(nine.plusSeconds(86400 + 3600))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        service.startReminderRun()
        assertEquals("TEMPORARY_FAILURE", state())
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
        clock.set(nine.plusSeconds(300))
        service.dispatchDue()
        assertEquals("DISCARDED", state())
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `restart discards interrupted reminder retries and never catches up missed run`() {
        booking(nine.plusSeconds(86400 + 3600))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        service.startReminderRun()
        service.recoverAfterRestart()
        clock.set(nine.plusSeconds(31))
        service.dispatchDue()
        assertEquals("DISCARDED", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        jdbc.execute("TRUNCATE notification_delivery_reservations,notification_deliveries RESTART IDENTITY CASCADE")
        service.recoverAfterRestart()
        service.dispatchDue()
        assertEquals(0L, count())
        clock.set(nine.plusSeconds(301))
        service.startReminderRun()
        assertEquals(0L, count())
    }

    @Test
    fun `accepted reminder suppresses pending confirmation while ambiguous reminder pauses it`() {
        val id = booking(nine.plusSeconds(86400 + 3600))
        queue(id)
        service.startReminderRun()
        assertEquals("DISCARDED", state("CREATE"))
        assertEquals("ACCEPTED", state("REMINDER"))
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        jdbc.execute("TRUNCATE notification_delivery_reservations,notification_deliveries RESTART IDENTITY CASCADE")
        service.recoverAfterRestart()
        queue(id)
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.AMBIGUOUS
        service.startReminderRun()
        assertEquals("PAUSED", state("CREATE"))
        assertEquals("AMBIGUOUS", state("REMINDER"))
        edit(id)
        changed(id, "EDIT", true)
        assertEquals("PAUSED", state("EDIT"))
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `restart unknown reminder pauses potentially conflicting pending confirmations`() {
        val id = booking(nine.plusSeconds(86400 + 3600))
        queue(id)
        transactions.executeWithoutResult {
            repository.insert(
                "REMINDER",
                details.reservation(id)!!,
                clock.instant(),
                nine.plusSeconds(300),
                java.util.UUID.randomUUID(),
            )
        }
        jdbc.update("UPDATE notification_deliveries SET state='SENDING',attempts=1 WHERE kind='REMINDER'")
        service.recoverAfterRestart()
        service.dispatchDue()
        assertEquals("AMBIGUOUS", state("REMINDER"))
        assertEquals("PAUSED", state("CREATE"))
        verify(exactly = 0) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `OTP and notifications share one atomic global daily SMS budget`() {
        val capped = OtpStore(redis, 1, org.springframework.mock.env.MockEnvironment())
        capped.issueCode("+48881111111")
        val budget = DailySmsBudget(redis, 1)
        assertFalse(budget.reserve().allowed)
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        assertTrue(budget.reserve().allowed)
        assertThrows<RateLimitExceededException> { capped.issueCode("+48882222222") }
    }

    private fun booking(start: Instant, status: String = "BOOKED", created: Instant = nine.minusSeconds(86400)): UUID =
        transactions.execute {
            jdbc.queryForObject(
                "SELECT set_config('scheduler.audit_time',?,true)",
                String::class.java,
                created.toString(),
            )
            jdbc.queryForObject(
                """
                INSERT INTO reservations(company_id,customer_id,employee_id,service_id,price,start_time,end_time,status,
                    accepted_service_name,agreed_duration_minutes,creation_source,created_at)
                VALUES (?,?,?,?,100,?,?,?,'Accepted name',30,'STAFF',?) RETURNING id
                """
                    .trimIndent(),
                UUID::class.java,
                companyId,
                customerId,
                employeeId,
                offeringId,
                Timestamp.from(start),
                Timestamp.from(start.plusSeconds(1800)),
                status,
                Timestamp.from(created),
            )!!
        }

    private fun edit(id: UUID, start: Instant? = null) {
        transactions.executeWithoutResult {
            jdbc.queryForObject(
                "SELECT set_config('scheduler.audit_time',?,true)",
                String::class.java,
                clock.instant().toString(),
            )
            if (start == null) jdbc.update("UPDATE reservations SET version=version+1,price=price+1 WHERE id=?", id)
            else
                jdbc.update(
                    "UPDATE reservations SET version=version+1,start_time=?,end_time=? WHERE id=?",
                    Timestamp.from(start),
                    Timestamp.from(start.plusSeconds(1800)),
                    id,
                )
        }
    }

    private fun changed(id: UUID, kind: String = "CREATE", notify: Boolean = true) {
        transactions.executeWithoutResult { events.publishEvent(ReservationChangedEvent(id, kind, notify)) }
    }

    private fun queue(id: UUID) {
        transactions.executeWithoutResult {
            repository.insert("CREATE", details.reservation(id)!!, clock.instant(), details.reservation(id)!!.start)
        }
    }

    private fun state(kind: String? = null): String =
        jdbc.queryForObject(
            "SELECT state FROM notification_deliveries" +
                (if (kind == null) "" else " WHERE kind=?") +
                " ORDER BY id DESC LIMIT 1",
            String::class.java,
            *if (kind == null) emptyArray() else arrayOf(kind),
        )!!

    private fun count(): Long = jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long::class.java)!!

    @TestConfiguration
    class TestTime {
        @Bean @Primary fun notificationClock(): NotificationClock = NotificationClock()
    }
}

/** Mutable UTC clock lets tests advance across Warsaw job boundaries without waiting. */
class NotificationClock : Clock() {
    private val current = AtomicReference(Instant.parse("2027-06-01T07:00:00Z"))

    /** Sets the authoritative test instant. */
    fun set(value: Instant) {
        current.set(value)
    }

    override fun instant(): Instant = current.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
}
