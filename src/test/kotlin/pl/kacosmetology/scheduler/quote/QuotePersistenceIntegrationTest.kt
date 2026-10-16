package pl.kacosmetology.scheduler.quote

import com.ninjasquad.springmockk.MockkBean
import jakarta.persistence.EntityManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import pl.kacosmetology.scheduler.TestcontainersConfiguration
import pl.kacosmetology.scheduler.auth.sms.SmsSender
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingRepository
import pl.kacosmetology.scheduler.quote.dto.CreateQuoteRequest
import software.amazon.awssdk.services.s3.S3Client

/** Uses PostgreSQL quote storage and migrated phone accounts, independently of the legacy global-user mapping. */
@SpringBootTest
@Import(TestcontainersConfiguration::class, QuotePersistenceIntegrationTest.FixedClock::class)
@Transactional
class QuotePersistenceIntegrationTest {
    @Autowired private lateinit var service: QuoteService
    @Autowired private lateinit var policy: QuotePolicy
    @Autowired private lateinit var repository: BookingQuoteRepository
    @Autowired private lateinit var companies: CompanyRepository
    @Autowired private lateinit var offerings: OfferingRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var entityManager: EntityManager
    @MockkBean private lateinit var smsSender: SmsSender
    @MockkBean private lateinit var s3Client: S3Client
    private var customerId = 0L
    private var companyId = 0L
    private var offeringId = 0L
    private val start = LocalDateTime.parse("2026-06-01T14:00:00")
    private val now = Instant.parse("2026-06-01T08:00:00Z")

    @BeforeEach
    fun setup() {
        customerId =
            requireNotNull(
                jdbc.queryForObject(
                    "INSERT INTO customer_accounts(phone_number) VALUES ('+48880000001') RETURNING id",
                    Long::class.java,
                ),
            )
        companyId = companies.save(Company(name = "Quote Salon", lastMinuteDiscountPercent = 50)).id!!
        offeringId =
            offerings.save(Offering(companyId = companyId, name = "Original", durationMinutes = 30, price = 101)).id!!
    }

    @Test
    fun `quote persists exact commercial snapshot without booking capacity or company profile`() {
        val created = service.create(customerId, CreateQuoteRequest(offeringId, start))
        entityManager.flush()
        entityManager.clear()
        val loaded = requireNotNull(repository.findByIdAndCustomerId(created.quoteId, customerId))
        assertEquals(companyId, loaded.companyId)
        assertEquals(offeringId, loaded.serviceId)
        assertEquals("Original", loaded.acceptedServiceName)
        assertEquals(50, loaded.price)
        assertEquals(30, loaded.durationMinutes)
        assertEquals(now, loaded.createdAt)
        assertEquals(now.plusSeconds(600), loaded.expiresAt)
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), loaded.startTime)
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM company_customers WHERE company_id=?",
                Long::class.java,
                companyId,
            ),
        )
        assertEquals(
            0L,
            jdbc.queryForObject("SELECT count(*) FROM reservations WHERE company_id=?", Long::class.java, companyId),
        )
        assertEquals(
            0L,
            jdbc.queryForObject(
                "SELECT count(*) FROM company_employees WHERE contact_phone='+48880000001'",
                Long::class.java,
            ),
        )
    }

    @Test
    fun `durable valid quote survives actual catalog deactivation and all commercial changes`() {
        val created = service.create(customerId, CreateQuoteRequest(offeringId, start))
        entityManager.flush()
        jdbc.update(
            "UPDATE offerings SET active=false, name='Changed', duration_minutes=90, price=1 WHERE id=?",
            offeringId,
        )
        entityManager.clear()
        assertEquals(
            CommercialAgreement("Original", 50, 30),
            policy.resolve(customerId, offeringId, start, created.quoteId, now.plusSeconds(599)),
        )
        val conflict =
            assertThrows<ApiException> {
                policy.resolve(customerId, offeringId, start, created.quoteId, now.plusSeconds(600))
            }
        assertEquals(ErrorCode.QUOTE_RECONFIRMATION_REQUIRED, conflict.code)
    }

    @Test
    fun `expired persisted quote accepts unchanged effective agreement and rejects new price`() {
        val created = service.create(customerId, CreateQuoteRequest(offeringId, start))
        entityManager.flush()
        entityManager.clear()
        assertEquals(
            CommercialAgreement("Original", 50, 30),
            policy.resolve(customerId, offeringId, start, created.quoteId, now.plusSeconds(600)),
        )
        jdbc.update("UPDATE offerings SET price=103 WHERE id=?", offeringId)
        entityManager.clear()
        assertEquals(
            ErrorCode.QUOTE_RECONFIRMATION_REQUIRED,
            assertThrows<ApiException> {
                    policy.resolve(customerId, offeringId, start, created.quoteId, now.plusSeconds(600))
                }
                .code,
        )
    }

    @Test
    fun `repository and policy never disclose another customer quote`() {
        val created = service.create(customerId, CreateQuoteRequest(offeringId, start))
        entityManager.flush()
        entityManager.clear()
        assertNull(repository.findByIdAndCustomerId(created.quoteId, customerId + 1))
        assertEquals(
            ErrorCode.NOT_FOUND,
            assertThrows<ApiException> { policy.resolve(customerId + 1, offeringId, start, created.quoteId, now) }.code,
        )
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertThrows<ApiException> {
                    policy.resolve(customerId, offeringId, start.plusMinutes(1), created.quoteId, now)
                }
                .code,
        )
    }

    @TestConfiguration
    class FixedClock {
        @Bean @Primary fun quoteClock(): Clock = Clock.fixed(Instant.parse("2026-06-01T08:00:00Z"), ZoneOffset.UTC)
    }
}
