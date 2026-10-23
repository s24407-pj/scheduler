package pl.kacosmetology.scheduler.offering

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import java.util.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.mock.web.MockMultipartFile
import pl.kacosmetology.scheduler.company.*
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignment
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.dto.OfferingRequest
import pl.kacosmetology.scheduler.testId

@ExtendWith(MockKExtension::class)
class OfferingServiceTest {

    @MockK private lateinit var companies: CompanyRepository

    @MockK private lateinit var offeringRepository: OfferingRepository

    @MockK private lateinit var offeringImageService: OfferingImageService

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @InjectMockKs private lateinit var offeringService: OfferingService

    private val companyId = testId(1)
    private val employeeId = testId(10)

    private val manicure =
        Offering(id = testId(1), companyId = companyId, name = "Manicure", durationMinutes = 60, price = 100)
    private val pedicure =
        Offering(id = testId(2), companyId = companyId, name = "Pedicure", durationMinutes = 60, price = 120)

    @BeforeEach
    fun companyLock() {
        every { companies.findByIdForUpdate(companyId) } returns Company(id = companyId, name = "Salon")
    }

    @Test
    fun `getPublicEmployeeOfferings should return only active offerings assigned at the company`() {
        every { companyEmployeeRepository.findByCompanyIdAndUserId(companyId, employeeId) } returns
            CompanyEmployee(
                companyId = companyId,
                userId = employeeId,
                role = "EMPLOYEE",
                eligibilityMode = EmployeeEligibilityMode.SPECIFIC_SERVICES,
            )
        every { assignmentRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId) } returns
            listOf(
                EmployeeOfferingAssignment(companyId = companyId, employeeId = employeeId, offeringId = testId(1)),
                // assigned but inactive: not returned by findAllByCompanyIdAndActiveTrue
                EmployeeOfferingAssignment(companyId = companyId, employeeId = employeeId, offeringId = testId(3)),
            )
        every { offeringRepository.findAllByCompanyIdAndActiveTrue(companyId) } returns listOf(manicure, pedicure)

        val result = offeringService.getPublicEmployeeOfferings(companyId, employeeId)

        assertEquals(listOf(manicure), result)
    }

    @Test
    fun `getPublicEmployeeOfferings should return every active company offering in explicit ALL_SERVICES mode`() {
        every { companyEmployeeRepository.findByCompanyIdAndUserId(companyId, employeeId) } returns
            CompanyEmployee(companyId = companyId, userId = employeeId, role = "EMPLOYEE")
        every { assignmentRepository.findAllByCompanyIdAndEmployeeId(companyId, employeeId) } returns emptyList()
        every { offeringRepository.findAllByCompanyIdAndActiveTrue(companyId) } returns listOf(manicure, pedicure)

        val result = offeringService.getPublicEmployeeOfferings(companyId, employeeId)

        assertEquals(listOf(manicure, pedicure), result)
    }

    @Test
    fun `getPublicEmployeeOfferings should throw when the employee is not employed at the company`() {
        every { companyEmployeeRepository.findByCompanyIdAndUserId(companyId, employeeId) } returns null

        assertThrows<NoSuchElementException> {
            offeringService.getPublicEmployeeOfferings(companyId, employeeId)
        }
        verify(exactly = 0) { offeringRepository.findAllByCompanyIdAndActiveTrue(any()) }
    }

    @Test
    fun `getCompanyOfferings should return list of offerings for given company`() {
        // GIVEN
        val offerings =
            listOf(
                Offering(id = testId(1), companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200),
                Offering(id = testId(2), companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80),
            )
        every { offeringRepository.findAllByCompanyIdAndActiveTrue(companyId) } returns offerings

        // WHEN
        val result = offeringService.getCompanyOfferings(companyId)

        // THEN
        assertEquals(2, result.size)
        assertEquals("Masaż", result[0].name)
    }

    @Test
    fun `getOfferingById should return offering when it exists`() {
        // GIVEN
        val offering =
            Offering(id = testId(1), companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200)
        every { offeringRepository.findById(testId(1)) } returns Optional.of(offering)

        // WHEN
        val result = offeringService.getOfferingById(testId(1))

        // THEN
        assertEquals("Masaż", result.name)
    }

    @Test
    fun `getOfferingById should throw when offering does not exist`() {
        // GIVEN
        every { offeringRepository.findById(testId(999)) } returns Optional.empty()

        // WHEN & THEN
        val exception =
            assertThrows<NoSuchElementException> {
                offeringService.getOfferingById(testId(999))
            }
        assertEquals("Usługa nie istnieje", exception.message)
    }

    @Test
    fun `createOffering should save offering with correct data`() {
        // GIVEN
        val request = OfferingRequest(name = "Masaż", durationMinutes = 60, price = 200)
        every { offeringRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = offeringService.createOffering(companyId, request)

        // THEN
        assertEquals(companyId, result.companyId)
        assertEquals("Masaż", result.name)
        assertEquals(60, result.durationMinutes)
        assertEquals(200, result.price)
        verify(exactly = 1) { offeringRepository.save(any()) }
    }

    @Test
    fun `updateOffering should update offering belonging to company`() {
        // GIVEN
        val existing =
            Offering(id = testId(1), companyId = companyId, name = "Stara", durationMinutes = 30, price = 100)
        val request = OfferingRequest(name = "Nowa", durationMinutes = 45, price = 150)

        every { offeringRepository.findById(testId(1)) } returns Optional.of(existing)
        // WHEN
        val result = offeringService.updateOffering(testId(1), companyId, request)

        // THEN
        assertEquals("Nowa", result.name)
        assertEquals(45, result.durationMinutes)
        assertEquals(150, result.price)
        assertEquals(companyId, result.companyId)
        assertSame(existing, result)
        verify(exactly = 0) { offeringRepository.save(any()) }
    }

    @Test
    fun `updateOffering should throw when offering belongs to different company`() {
        // GIVEN
        val existing =
            Offering(id = testId(1), companyId = testId(999), name = "Obca", durationMinutes = 30, price = 100)
        val request = OfferingRequest(name = "Hack", durationMinutes = 10, price = 0)

        every { offeringRepository.findById(testId(1)) } returns Optional.of(existing)

        // WHEN & THEN
        val exception =
            assertThrows<NoSuchElementException> {
                offeringService.updateOffering(testId(1), companyId, request)
            }
        assertEquals("Usługa nie istnieje", exception.message)
        verify(exactly = 0) { offeringRepository.save(any()) }
    }

    @Test
    fun `deleteOffering should soft-delete offering belonging to company`() {
        // GIVEN
        val existing =
            Offering(id = testId(1), companyId = companyId, name = "Do usunięcia", durationMinutes = 30, price = 50)
        every { offeringRepository.findById(testId(1)) } returns Optional.of(existing)
        // WHEN
        offeringService.deleteOffering(testId(1), companyId)

        // THEN
        assertFalse(existing.active)
        verify(exactly = 0) { offeringRepository.save(any()) }
        verify(exactly = 0) { offeringRepository.deleteById(any()) }
    }

    @Test
    fun `deleteOffering should throw when offering belongs to different company`() {
        // GIVEN
        val existing =
            Offering(id = testId(1), companyId = testId(999), name = "Obca", durationMinutes = 30, price = 100)
        every { offeringRepository.findById(testId(1)) } returns Optional.of(existing)

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            offeringService.deleteOffering(testId(1), companyId)
        }
        verify(exactly = 0) { offeringRepository.save(any()) }
    }

    @Test
    fun `deleteOffering should throw when offering does not exist`() {
        // GIVEN
        every { offeringRepository.findById(testId(999)) } returns Optional.empty()

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            offeringService.deleteOffering(testId(999), companyId)
        }
    }

    @Test
    fun `uploadImage should delegate to offeringImageService and return offering`() {
        // GIVEN
        val offering =
            Offering(id = testId(1), companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200)
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(100))
        every { offeringRepository.findById(testId(1)) } returns Optional.of(offering)
        every { offeringImageService.upload(companyId, testId(1), file) } returns
            OfferingImage(id = testId(10), offeringId = testId(1), imageUrl = "http://cdn/img.jpg")

        // WHEN
        val result = offeringService.uploadImage(testId(1), companyId, file)

        // THEN
        assertEquals(offering, result)
        verify(exactly = 1) { offeringImageService.upload(companyId, testId(1), file) }
    }

    @Test
    fun `uploadImage should throw when offering belongs to different company`() {
        // GIVEN
        val offering =
            Offering(id = testId(1), companyId = testId(999), name = "Obca", durationMinutes = 30, price = 100)
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(100))
        every { offeringRepository.findById(testId(1)) } returns Optional.of(offering)

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            offeringService.uploadImage(testId(1), companyId, file)
        }
        verify(exactly = 0) { offeringImageService.upload(any(), any(), any()) }
    }

    @Test
    fun `deleteImage should delegate to offeringImageService and return offering`() {
        // GIVEN
        val offering =
            Offering(id = testId(1), companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200)
        every { offeringRepository.findById(testId(1)) } returns Optional.of(offering)
        every { offeringImageService.delete(testId(42), testId(1)) } returns Unit

        // WHEN
        val result = offeringService.deleteImage(testId(1), testId(42), companyId)

        // THEN
        assertEquals(offering, result)
        verify(exactly = 1) { offeringImageService.delete(testId(42), testId(1)) }
    }

    @Test
    fun `deleteImage should throw when offering belongs to different company`() {
        // GIVEN
        val offering =
            Offering(id = testId(1), companyId = testId(999), name = "Obca", durationMinutes = 30, price = 100)
        every { offeringRepository.findById(testId(1)) } returns Optional.of(offering)

        // WHEN & THEN
        assertThrows<NoSuchElementException> {
            offeringService.deleteImage(testId(1), testId(42), companyId)
        }
        verify(exactly = 0) { offeringImageService.delete(any(), any()) }
    }
}
