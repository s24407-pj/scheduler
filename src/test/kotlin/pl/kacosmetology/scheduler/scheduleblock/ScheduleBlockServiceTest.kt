package pl.kacosmetology.scheduler.scheduleblock

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import io.mockk.verifyOrder
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityConflict
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityConflictSource
import pl.kacosmetology.scheduler.availability.EmployeeAvailabilityPolicy
import pl.kacosmetology.scheduler.company.Company
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.company.CompanyRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.testId

@ExtendWith(MockKExtension::class)
class ScheduleBlockServiceTest {

    @MockK private lateinit var scheduleBlockRepository: ScheduleBlockRepository

    @MockK private lateinit var employeeAvailabilityPolicy: EmployeeAvailabilityPolicy

    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository

    @MockK private lateinit var companyRepository: CompanyRepository

    @InjectMockKs private lateinit var scheduleBlockService: ScheduleBlockService

    private val employeeId = testId(10)
    private val companyId = testId(1)
    private val startTime = LocalDateTime.of(2030, 6, 1, 10, 0)
    private val endTime = LocalDateTime.of(2030, 6, 1, 11, 0)

    private fun mockLockedMembership() {
        every {
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId)
        } returns CompanyEmployee(companyId = companyId, userId = employeeId, role = "EMPLOYEE")
    }

    @BeforeEach
    fun mockPolicyLocks() {
        every { companyRepository.findByIdForUpdate(companyId) } returns Company(id = companyId, name = "Firma")
        every { companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, any()) } answers
            {
                CompanyEmployee(companyId = companyId, userId = secondArg(), role = "EMPLOYEE")
            }
    }

    @Test
    fun `createBlock should save and return block when no overlaps exist`() {
        // GIVEN
        mockLockedMembership()
        every { employeeAvailabilityPolicy.findFirstConflict(companyId, employeeId, startTime, endTime) } returns null
        every { scheduleBlockRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = scheduleBlockService.createBlock(employeeId, companyId, startTime, endTime, "Przerwa")

        // THEN
        assertNotNull(result)
        assertEquals(employeeId, result.employeeId)
        assertEquals(companyId, result.companyId)
        assertEquals("Przerwa", result.reason)
        verify(exactly = 1) { scheduleBlockRepository.save(any()) }
        verifyOrder {
            companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId)
            employeeAvailabilityPolicy.findFirstConflict(companyId, employeeId, startTime, endTime)
            scheduleBlockRepository.save(any())
        }
    }

    @Test
    fun `createBlock should throw when endTime is not after startTime`() {
        // GIVEN - end == start
        val sameTime = startTime
        mockLockedMembership()

        // WHEN & THEN
        val exception =
            assertThrows<IllegalArgumentException> {
                scheduleBlockService.createBlock(employeeId, companyId, startTime, sameTime, null)
            }
        assertEquals("Czas zakończenia musi być późniejszy niż czas rozpoczęcia", exception.message)
        verify(exactly = 0) { scheduleBlockRepository.save(any()) }
    }

    @Test
    fun `createBlock should throw when existing reservation overlaps`() {
        // GIVEN
        mockLockedMembership()
        every {
            employeeAvailabilityPolicy.findFirstConflict(companyId, employeeId, startTime, endTime)
        } returns
            EmployeeAvailabilityConflict(
                source = EmployeeAvailabilityConflictSource.RESERVATION,
                startTime = startTime,
                endTime = endTime,
            )

        // WHEN & THEN
        val exception =
            assertThrows<ApiException> {
                scheduleBlockService.createBlock(employeeId, companyId, startTime, endTime, null)
            }
        assertEquals("W tym czasie istnieje już rezerwacja", exception.message)
        assertEquals(ErrorCode.SLOT_UNAVAILABLE, exception.code)
        verify(exactly = 0) { scheduleBlockRepository.save(any()) }
    }

    @Test
    fun `createBlock should throw when another block overlaps`() {
        // GIVEN
        mockLockedMembership()
        every {
            employeeAvailabilityPolicy.findFirstConflict(companyId, employeeId, startTime, endTime)
        } returns
            EmployeeAvailabilityConflict(
                source = EmployeeAvailabilityConflictSource.SCHEDULE_BLOCK,
                startTime = startTime,
                endTime = endTime,
            )

        // WHEN & THEN
        val exception =
            assertThrows<ApiException> {
                scheduleBlockService.createBlock(employeeId, companyId, startTime, endTime, null)
            }
        assertEquals("W tym czasie istnieje już blokada", exception.message)
        assertEquals(ErrorCode.SLOT_UNAVAILABLE, exception.code)
        verify(exactly = 0) { scheduleBlockRepository.save(any()) }
    }

    @Test
    fun `createBlock should reject employee outside company before conflict query or save`() {
        every { companyEmployeeRepository.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) } returns null

        assertThrows<NoSuchElementException> {
            scheduleBlockService.createBlock(employeeId, companyId, startTime, endTime, null)
        }

        verify(exactly = 0) { employeeAvailabilityPolicy.findFirstConflict(any(), any(), any(), any()) }
        verify(exactly = 0) { scheduleBlockRepository.save(any()) }
    }

    @Test
    fun `getEmployeeBlocks should return company scoped blocks for company employee`() {
        val block =
            ScheduleBlock(
                companyId = companyId,
                employeeId = employeeId,
                startTime = startTime,
                endTime = endTime,
            )
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns true
        every {
            scheduleBlockRepository.findOverlapping(companyId, employeeId, startTime, endTime)
        } returns listOf(block)

        val result = scheduleBlockService.getEmployeeBlocks(companyId, employeeId, startTime, endTime)

        assertEquals(listOf(block), result)
    }

    @Test
    fun `getEmployeeBlocks should reject employee outside company before repository query`() {
        every { companyEmployeeRepository.existsByCompanyIdAndUserId(companyId, employeeId) } returns false

        assertThrows<NoSuchElementException> {
            scheduleBlockService.getEmployeeBlocks(companyId, employeeId, startTime, endTime)
        }

        verify(exactly = 0) { scheduleBlockRepository.findOverlapping(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteBlock should delete when employee owns the block`() {
        // GIVEN
        val blockId = testId(99)
        val block =
            ScheduleBlock(
                id = blockId,
                companyId = companyId,
                employeeId = employeeId,
                startTime = startTime,
                endTime = endTime,
            )
        every { scheduleBlockRepository.findByIdAndCompanyId(blockId, companyId) } returns block
        every { scheduleBlockRepository.delete(block) } returns Unit

        // WHEN
        scheduleBlockService.deleteBlock(blockId, requesterId = employeeId, isOwner = false, companyId = companyId)

        // THEN
        verify(exactly = 1) { scheduleBlockRepository.delete(block) }
    }

    @Test
    fun `deleteBlock should throw when block does not exist`() {
        // GIVEN
        val blockId = testId(999)
        every { scheduleBlockRepository.findByIdAndCompanyId(blockId, companyId) } returns null

        // WHEN & THEN
        val exception =
            assertThrows<ApiException> {
                scheduleBlockService.deleteBlock(
                    blockId,
                    requesterId = employeeId,
                    isOwner = false,
                    companyId = companyId,
                )
            }
        assertEquals(ErrorCode.SCHEDULE_BLOCK_NOT_FOUND, exception.code)
    }

    @Test
    fun `deleteBlock should throw when employee owns block in another company`() {
        val blockId = testId(99)
        val block =
            ScheduleBlock(
                id = blockId,
                companyId = testId(999),
                employeeId = employeeId,
                startTime = startTime,
                endTime = endTime,
            )
        every { scheduleBlockRepository.findByIdAndCompanyId(block.id!!, companyId) } returns null

        val exception =
            assertThrows<ApiException> {
                scheduleBlockService.deleteBlock(
                    blockId,
                    requesterId = employeeId,
                    isOwner = false,
                    companyId = companyId,
                )
            }

        assertEquals("Blokada nie istnieje", exception.message)
        assertEquals(ErrorCode.SCHEDULE_BLOCK_NOT_FOUND, exception.code)
        verify(exactly = 0) { scheduleBlockRepository.delete(any()) }
    }

    @Test
    fun `deleteBlock should throw when employee does not own the block`() {
        // GIVEN
        val blockId = testId(99)
        val otherEmployeeId = testId(999)
        val block =
            ScheduleBlock(
                id = blockId,
                companyId = companyId,
                employeeId = otherEmployeeId,
                startTime = startTime,
                endTime = endTime,
            )
        every { scheduleBlockRepository.findByIdAndCompanyId(blockId, companyId) } returns block

        // WHEN & THEN
        val exception =
            assertThrows<ApiException> {
                scheduleBlockService.deleteBlock(
                    blockId,
                    requesterId = employeeId,
                    isOwner = false,
                    companyId = companyId,
                )
            }
        assertEquals("Nie możesz usunąć cudzej blokady", exception.message)
        assertEquals(ErrorCode.ACCESS_DENIED, exception.code)
        verify(exactly = 0) { scheduleBlockRepository.delete(any()) }
    }

    @Test
    fun `deleteBlock should allow owner to delete any block in their company`() {
        // GIVEN
        val blockId = testId(99)
        val otherEmployeeId = testId(999)
        val block =
            ScheduleBlock(
                id = blockId,
                companyId = companyId,
                employeeId = otherEmployeeId,
                startTime = startTime,
                endTime = endTime,
            )
        every { scheduleBlockRepository.findByIdAndCompanyId(blockId, companyId) } returns block
        every { scheduleBlockRepository.delete(block) } returns Unit

        // WHEN
        scheduleBlockService.deleteBlock(blockId, requesterId = testId(1), isOwner = true, companyId = companyId)

        // THEN
        verify(exactly = 1) { scheduleBlockRepository.delete(block) }
    }

    @Test
    fun `deleteBlock should throw when owner tries to delete block from another company`() {
        // GIVEN
        val blockId = testId(99)
        val otherCompanyId = testId(999)
        val block =
            ScheduleBlock(
                id = blockId,
                companyId = otherCompanyId,
                employeeId = testId(5),
                startTime = startTime,
                endTime = endTime,
            )
        every { scheduleBlockRepository.findByIdAndCompanyId(block.id!!, companyId) } returns null

        // WHEN & THEN
        val exception =
            assertThrows<ApiException> {
                scheduleBlockService.deleteBlock(
                    blockId,
                    requesterId = testId(1),
                    isOwner = true,
                    companyId = companyId,
                )
            }
        assertEquals("Blokada nie istnieje", exception.message)
        assertEquals(ErrorCode.SCHEDULE_BLOCK_NOT_FOUND, exception.code)
        verify(exactly = 0) { scheduleBlockRepository.delete(any()) }
    }
}
