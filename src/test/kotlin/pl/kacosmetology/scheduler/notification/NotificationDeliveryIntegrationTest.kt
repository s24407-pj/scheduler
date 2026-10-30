package pl.kacosmetology.scheduler.notification

import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.MockkSpyBean
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.AuthService
import pl.kacosmetology.scheduler.auth.OtpDeliveryService
import pl.kacosmetology.scheduler.auth.OtpStore
import pl.kacosmetology.scheduler.auth.RateLimitExceededException
import pl.kacosmetology.scheduler.auth.dto.RequestCodeRequest
import pl.kacosmetology.scheduler.auth.sms.DailySmsBudget
import pl.kacosmetology.scheduler.auth.sms.SmsDeliveryResult
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.config.SmsWorkers
import pl.kacosmetology.scheduler.reservation.CustomerBlockedBookingsEvent
import pl.kacosmetology.scheduler.reservation.ReservationChangedEvent
import pl.kacosmetology.scheduler.testId
import software.amazon.awssdk.services.s3.S3Client

/** End-to-end transaction, delivery-state and reminder-clock evidence against PostgreSQL and Redis. */
@SpringBootTest(properties = ["scheduler.jobs.enabled=false"])
@Import(TestcontainersConfiguration::class)
class NotificationDeliveryIntegrationTest {
    @Autowired private lateinit var workers: SmsWorkers
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var otpDeliveries: OtpDeliveryService
    @Autowired private lateinit var service: NotificationService
    @MockkSpyBean private lateinit var repository: NotificationDeliveryRepository
    @Autowired private lateinit var details: NotificationDetails
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var source: DataSource
    @TestBean(name = "clock", methodName = "notificationClock", enforceOverride = true)
    private lateinit var applicationClock: Clock

    private val clock: NotificationClock
        get() = applicationClock as NotificationClock

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
        awaitWorkers()
        clock.set(nine)
        transactions = TransactionTemplate(manager)
        jdbc.execute("TRUNCATE companies,company_customers RESTART IDENTITY CASCADE")
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        service.recoverAfterRestart()
        clearMocks(sender)
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.ACCEPTED
        companyId = jdbc.queryForObject<UUID>("INSERT INTO companies(name) VALUES ('Local Salon') RETURNING id")!!
        customerId =
            jdbc.queryForObject<UUID>(
                "INSERT INTO company_customers(company_id,phone_number) VALUES (?,'+48880000001') RETURNING id",
                companyId,
            )!!
        offeringId =
            jdbc.queryForObject<UUID>(
                "INSERT INTO offerings(company_id,name,duration_minutes,price) VALUES (?,'Current catalog',30,100) RETURNING id",
                companyId,
            )!!
        jdbc.update(
            "INSERT INTO company_employees(company_id,user_id,role,first_name,last_name,login_email) VALUES (?,?,'EMPLOYEE','Company','Employee','employee@local.test')",
            companyId,
            employeeId,
        )
    }

    @Test
    fun `rollback and default staff optout emit no confirmation`() {
        val id = booking(nine.plusSeconds(3600))
        assertThrows<IllegalStateException> {
            transactions.executeWithoutResult {
                events.publishEvent(ReservationChangedEvent(id, "CREATE", true))
                error("rollback")
            }
        }
        assertEquals(0L, count())
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
        dispatchAndAwait()
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
        dispatchAndAwait()
        assertEquals("ACCEPTED", state())
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
        edit(id)
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.AMBIGUOUS
        changed(id, "EDIT")
        assertEquals("AMBIGUOUS", state())
        clock.set(nine.plusSeconds(90))
        dispatchAndAwait()
        verify(exactly = 3) { sender.deliverMessage(any(), any()) }
        edit(id)
        every { sender.deliverMessage(any(), any()) } throws IllegalStateException("timeout")
        changed(id, "EDIT")
        assertEquals("AMBIGUOUS", state())
        clock.set(nine.plusSeconds(120))
        dispatchAndAwait()
        verify(exactly = 4) { sender.deliverMessage(any(), any()) }
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
        dispatchAndAwait()
        assertEquals("ACCEPTED", state())
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `future message retry stops at exact start and ongoing cancellation is opt in only once`() {
        val id = booking(nine.plusSeconds(60))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        changed(id)
        clock.set(nine.plusSeconds(60))
        dispatchAndAwait()
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
        dispatchAndAwait()
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
        dispatchAndAwait()
        assertEquals("DISCARDED", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        edit(id, nine.plusSeconds(10800))
        changed(id, "EDIT", true)
        assertEquals(
            Timestamp.from(nine.plusSeconds(10800)),
            jdbc.queryForObject<Timestamp>(
                "SELECT deadline FROM notification_deliveries ORDER BY id DESC LIMIT 1",
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
        dispatchAndAwait()
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
        dispatchAndAwait()
        assertTrue(text.captured.contains("11:00"))
        assertFalse(text.captured.contains("10:00"))
        assertEquals("TEMPORARY_FAILURE", state())
        jdbc.update("UPDATE reservations SET status='BOOKED' WHERE id=?", second)
        clock.set(nine.plusSeconds(31))
        dispatchAndAwait()
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
        startAndAwait()
        assertEquals("ACCEPTED", state())
        edit(id, nine.plusSeconds(86400 + 7200))
        changed(id, "EDIT", false)
        startAndAwait()
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        edit(id, nine.plusSeconds(2 * 86400 + 3600))
        clock.set(nine.plusSeconds(86400))
        startAndAwait()
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
        startAndAwait()
        assertEquals(1L, count())
        assertEquals(
            sameDay,
            jdbc.queryForObject<UUID>("SELECT reservation_id FROM notification_deliveries"),
        )
    }

    @Test
    fun `temporary reminder retry remains eligible after 0905 and 0930 but stops exactly at 1000`() {
        booking(nine.plusSeconds(86400 + 3600))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        startAndAwait()
        assertEquals("TEMPORARY_FAILURE", state())
        for (elapsed in listOf(301L, 1800L)) {
            clock.set(nine.plusSeconds(elapsed))
            dispatchAndAwait()
            assertEquals("TEMPORARY_FAILURE", state())
        }
        verify(exactly = 3) { sender.deliverMessage(any(), any()) }
        clock.set(nine.plusSeconds(3600))
        dispatchAndAwait()
        assertEquals("DISCARDED", state())
        verify(exactly = 3) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `pending reminder may succeed at 095959 with deadline persisted as 1000`() {
        booking(nine.plusSeconds(86400 + 3600))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        startAndAwait()
        assertEquals(
            nine.plusSeconds(3600),
            jdbc.queryForObject<Timestamp>("SELECT deadline FROM notification_deliveries")!!.toInstant(),
        )
        clock.set(nine.plusSeconds(3599))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.ACCEPTED
        dispatchAndAwait()
        assertEquals("ACCEPTED", state())
        verify(exactly = 2) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `restart discards interrupted reminder retries and never catches up missed run`() {
        booking(nine.plusSeconds(86400 + 3600))
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.DEFINITE_TEMPORARY_FAILURE
        startAndAwait()
        service.recoverAfterRestart()
        clock.set(nine.plusSeconds(31))
        dispatchAndAwait()
        assertEquals("DISCARDED", state())
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        jdbc.execute("TRUNCATE notification_delivery_reservations,notification_deliveries RESTART IDENTITY CASCADE")
        service.recoverAfterRestart()
        dispatchAndAwait()
        assertEquals(0L, count())
        clock.set(nine.plusSeconds(3601))
        startAndAwait()
        assertEquals(0L, count())
    }

    @Test
    fun `accepted reminder suppresses pending confirmation while ambiguous reminder pauses it`() {
        val id = booking(nine.plusSeconds(86400 + 3600))
        queue(id)
        startAndAwait()
        assertEquals("DISCARDED", state("CREATE"))
        assertEquals("ACCEPTED", state("REMINDER"))
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        jdbc.execute("TRUNCATE notification_delivery_reservations,notification_deliveries RESTART IDENTITY CASCADE")
        service.recoverAfterRestart()
        queue(id)
        every { sender.deliverMessage(any(), any()) } returns SmsDeliveryResult.AMBIGUOUS
        startAndAwait()
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
                nine.plusSeconds(3600),
                UUID.randomUUID(),
            )
        }
        jdbc.update("UPDATE notification_deliveries SET state='SENDING',attempts=1 WHERE kind='REMINDER'")
        service.recoverAfterRestart()
        dispatchAndAwait()
        assertEquals("AMBIGUOUS", state("REMINDER"))
        assertEquals("PAUSED", state("CREATE"))
        verify(exactly = 0) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `OTP and notifications share one atomic global daily SMS budget`() {
        val capped = OtpStore(redis, 1, org.springframework.mock.env.MockEnvironment())
        capped.issueCode(companyId, "+48881111111")
        val budget = DailySmsBudget(redis, 1)
        assertFalse(budget.reserve().allowed)
        redis.connectionFactory!!.connection.use { it.serverCommands().flushAll() }
        assertTrue(budget.reserve().allowed)
        assertThrows<RateLimitExceededException> { capped.issueCode(companyId, "+48882222222") }
    }

    @Test
    fun `blocked notification pool does not block after commit listeners scheduler or OTP`() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val otpSent = CountDownLatch(1)
        every { sender.deliverMessage(any(), any()) } answers
            {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                SmsDeliveryResult.ACCEPTED
            }
        every { sender.deliverOtp(any(), any()) } answers
            {
                otpSent.countDown()
                SmsDeliveryResult.ACCEPTED
            }
        val ids =
            (1..5).map { index ->
                val customer =
                    jdbc.queryForObject<UUID>(
                        "INSERT INTO company_customers(company_id,phone_number) VALUES (?,?) RETURNING id",
                        companyId,
                        "+4888010000$index",
                    )!!
                booking(nine.plusSeconds(index * 3600L), customer = customer)
            }
        ids.take(4).forEach(::queue)
        otpDeliveries.recoverAfterRestart()
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2)) { service.dispatchDue() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                transactions.executeWithoutResult {
                    events.publishEvent(ReservationChangedEvent(ids.last(), "CREATE", true))
                }
                service.startReminderRun()
                auth.requestCode(RequestCodeRequest(companyId, "+48889999999"), "isolated-otp")
            }
            assertTrue(otpSent.await(5, TimeUnit.SECONDS))
            assertEquals(
                4,
                jdbc.queryForObject<Int>(
                    "SELECT count(*) FROM notification_deliveries WHERE state='SENDING'",
                ),
            )
            assertEquals(
                1,
                jdbc.queryForObject<Int>(
                    "SELECT count(*) FROM notification_deliveries WHERE state='PENDING'",
                ),
            )
        } finally {
            release.countDown()
            awaitWorkers()
        }
        assertEquals(
            5,
            jdbc.queryForObject<Int>("SELECT count(*) FROM notification_deliveries WHERE state='ACCEPTED'"),
        )
    }

    @Test
    fun `queued reminder is discarded at 1000 while earlier provider attempts retain their real outcome`() {
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        every { sender.deliverMessage(any(), any()) } answers
            {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                SmsDeliveryResult.ACCEPTED
            }
        repeat(5) { index ->
            val customer =
                jdbc.queryForObject<UUID>(
                    "INSERT INTO company_customers(company_id,phone_number) VALUES (?,?) RETURNING id",
                    companyId,
                    "+4888020000$index",
                )!!
            booking(nine.plusSeconds(86400 + index * 3600L), customer = customer)
        }
        try {
            service.startReminderRun()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            clock.set(nine.plusSeconds(3600))
        } finally {
            release.countDown()
            awaitWorkers()
        }
        assertEquals(
            4,
            jdbc.queryForObject<Int>("SELECT count(*) FROM notification_deliveries WHERE state='ACCEPTED'"),
        )
        assertEquals(
            1,
            jdbc.queryForObject<Int>(
                "SELECT count(*) FROM notification_deliveries WHERE state='DISCARDED' AND attempts=0",
            ),
        )
        verify(exactly = 4) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `repeated wakeups serialize one customer and suppress confirmation after reminder outcome`() {
        val id = booking(nine.plusSeconds(86400))
        queue(id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { sender.deliverMessage(any(), any()) } answers
            {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                SmsDeliveryResult.ACCEPTED
            }
        try {
            service.startReminderRun()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) { service.dispatchDue() }
            assertEquals(
                1,
                jdbc.queryForObject<Int>(
                    "SELECT count(*) FROM notification_deliveries WHERE state='SENDING' AND kind='REMINDER'",
                ),
            )
            assertEquals(
                "PENDING",
                jdbc.queryForObject<String>(
                    "SELECT state FROM notification_deliveries WHERE kind='CREATE'",
                ),
            )
        } finally {
            release.countDown()
            awaitWorkers()
        }
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        assertEquals("DISCARDED", state("CREATE"))
        assertEquals("ACCEPTED", state("REMINDER"))
    }

    @Test
    fun `temporary database failure retains requested run and its token until initialization commits`() {
        booking(nine.plusSeconds(86400))
        val tokens = mutableListOf<UUID>()
        every { repository.insert("REMINDER", any(), any(), any(), any()) } answers
            {
                tokens.add(arg(4))
                callOriginal().also {
                    if (tokens.size == 1) throw DataAccessResourceFailureException("temporary failure")
                }
            }
        startAndAwait()
        assertEquals(0L, count(), "Partial initialization must roll back")
        clock.set(nine.plusSeconds(31))
        dispatchAndAwait()
        assertEquals("ACCEPTED", state("REMINDER"))
        assertEquals(2, tokens.size)
        assertEquals(tokens[0], tokens[1], "A retry must retain the same run identity")
        dispatchAndAwait()
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        verify(exactly = 2) { repository.insert("REMINDER", any(), any(), any(), any()) }
    }

    @Test
    fun `failed reminder initialization expires at ten and cannot leak into the next day`() {
        booking(nine.plusSeconds(86400))
        every { repository.insert("REMINDER", any(), any(), any(), any()) } throws
            DataAccessResourceFailureException("temporary failure")
        startAndAwait()
        clock.set(nine.plusSeconds(3600))
        dispatchAndAwait()
        clock.set(nine.plusSeconds(86400))
        dispatchAndAwait()
        assertEquals(0L, count())
        verify(exactly = 1) { repository.insert("REMINDER", any(), any(), any(), any()) }
        verify(exactly = 0) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `restart forgets failed initialization even within the original delivery window`() {
        booking(nine.plusSeconds(86400))
        every { repository.insert("REMINDER", any(), any(), any(), any()) } throws
            DataAccessResourceFailureException("temporary failure")
        startAndAwait()
        service.recoverAfterRestart()
        clock.set(nine.plusSeconds(31))
        dispatchAndAwait()
        assertEquals(0L, count())
        verify(exactly = 1) { repository.insert("REMINDER", any(), any(), any(), any()) }
        verify(exactly = 0) { sender.deliverMessage(any(), any()) }
    }

    @Test
    fun `operator resolution of a failed reminder result pauses confirmations without resending`() {
        val id = booking(nine.plusSeconds(86400))
        queue(id)
        every { repository.state(any(), DeliveryState.ACCEPTED, any(), any(), any()) } throws
            DataAccessResourceFailureException("result commit failed")
        startAndAwait()
        assertEquals("SENDING", state("REMINDER"))
        assertEquals("PENDING", state("CREATE"))
        val deliveryId = jdbc.queryForObject<UUID>("SELECT id FROM notification_deliveries WHERE kind='REMINDER'")!!
        jdbc.update(
            "UPDATE notification_deliveries SET updated_at=CURRENT_TIMESTAMP - INTERVAL '3 minutes' WHERE id=?",
            deliveryId,
        )
        val updated =
            jdbc
                .queryForObject<Timestamp>(
                    "SELECT updated_at FROM notification_deliveries WHERE id=?",
                    deliveryId,
                )!!
                .toInstant()
        val sql =
            Files.readString(Path.of("scripts/ops/resolve-notification-sending.sql"))
                .lineSequence()
                .filterNot { it.startsWith("\\") }
                .joinToString("\n")
                .replace(":'delivery_id'", "'$deliveryId'")
                .replace(":'expected_updated_at'", "'$updated'")
        jdbc.execute(sql)
        assertEquals("AMBIGUOUS", state("REMINDER"))
        assertEquals("PAUSED", state("CREATE"))
        dispatchAndAwait()
        jdbc.execute(sql) // A repeated command is a no-op, including the confirmation side effect.
        verify(exactly = 1) { sender.deliverMessage(any(), any()) }
        assertEquals(
            1,
            jdbc.queryForObject<Int>("SELECT attempts FROM notification_deliveries WHERE id=?", deliveryId),
        )
    }

    @AfterEach
    fun awaitWorkers() {
        assertTrue(workers.awaitIdle(), "SMS workers did not settle")
    }

    private fun dispatchAndAwait() {
        service.dispatchDue()
        awaitWorkers()
    }

    private fun startAndAwait() {
        service.startReminderRun()
        awaitWorkers()
    }

    private fun booking(
        start: Instant,
        status: String = "BOOKED",
        created: Instant = nine.minusSeconds(86400),
        customer: UUID = customerId,
    ): UUID = transactions.execute {
        jdbc.queryForObject<String>(
            "SELECT set_config('scheduler.audit_time',?,true)",
            created.toString(),
        )
        jdbc.queryForObject<UUID>(
            """
            INSERT INTO reservations(company_id,customer_id,employee_id,service_id,price,start_time,end_time,status,
                accepted_service_name,agreed_duration_minutes,creation_source,created_at)
            VALUES (?,?,?,?,100,?,?,?,'Accepted name',30,'STAFF',?) RETURNING id
            """
                .trimIndent(),
            companyId,
            customer,
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
            jdbc.queryForObject<String>(
                "SELECT set_config('scheduler.audit_time',?,true)",
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
        awaitWorkers()
    }

    private fun queue(id: UUID) {
        transactions.executeWithoutResult {
            repository.insert("CREATE", details.reservation(id)!!, clock.instant(), details.reservation(id)!!.start)
        }
    }

    private fun state(kind: String? = null): String {
        awaitWorkers()
        return jdbc.queryForObject<String>(
            "SELECT state FROM notification_deliveries" +
                (if (kind == null) "" else " WHERE kind=?") +
                " ORDER BY id DESC LIMIT 1",
            *if (kind == null) emptyArray() else arrayOf(kind),
        )!!
    }

    private fun count(): Long = jdbc.queryForObject<Long>("SELECT count(*) FROM notification_deliveries")!!

    companion object {
        @JvmStatic fun notificationClock(): NotificationClock = NotificationClock()
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

    override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
}
