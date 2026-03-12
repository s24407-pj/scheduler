package pl.kacosmetology.scheduler.offering

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.mock.web.MockMultipartFile
import pl.kacosmetology.scheduler.offering.dto.OfferingRequest
import java.util.*

@ExtendWith(MockKExtension::class)
class OfferingServiceTest {

    @MockK
    private lateinit var offeringRepository: OfferingRepository

    @MockK
    private lateinit var offeringImageService: OfferingImageService

    @InjectMockKs
    private lateinit var offeringService: OfferingService

    private val companyId = 1L

    @Test
    fun `getCompanyOfferings should return list of offerings for given company`() {
        // GIVEN
        val offerings = listOf(
            Offering(id = 1, companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200),
            Offering(id = 2, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
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
        val offering = Offering(id = 1, companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200)
        every { offeringRepository.findById(1L) } returns Optional.of(offering)

        // WHEN
        val result = offeringService.getOfferingById(1L)

        // THEN
        assertEquals("Masaż", result.name)
    }

    @Test
    fun `getOfferingById should throw when offering does not exist`() {
        // GIVEN
        every { offeringRepository.findById(999L) } returns Optional.empty()

        // WHEN & THEN
        val exception = assertThrows<IllegalArgumentException> {
            offeringService.getOfferingById(999L)
        }
        assertEquals("Usługa o ID 999 nie istnieje", exception.message)
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
        val existing = Offering(id = 1, companyId = companyId, name = "Stara", durationMinutes = 30, price = 100)
        val request = OfferingRequest(name = "Nowa", durationMinutes = 45, price = 150)

        every { offeringRepository.findById(1L) } returns Optional.of(existing)
        every { offeringRepository.save(any()) } answers { firstArg() }

        // WHEN
        val result = offeringService.updateOffering(1L, companyId, request)

        // THEN
        assertEquals("Nowa", result.name)
        assertEquals(45, result.durationMinutes)
        assertEquals(150, result.price)
        assertEquals(companyId, result.companyId)
    }

    @Test
    fun `updateOffering should throw when offering belongs to different company`() {
        // GIVEN
        val existing = Offering(id = 1, companyId = 999L, name = "Obca", durationMinutes = 30, price = 100)
        val request = OfferingRequest(name = "Hack", durationMinutes = 10, price = 0)

        every { offeringRepository.findById(1L) } returns Optional.of(existing)

        // WHEN & THEN
        val exception = assertThrows<IllegalStateException> {
            offeringService.updateOffering(1L, companyId, request)
        }
        assertEquals("Brak dostępu do tej usługi", exception.message)
        verify(exactly = 0) { offeringRepository.save(any()) }
    }

    @Test
    fun `deleteOffering should soft-delete offering belonging to company`() {
        // GIVEN
        val existing =
            Offering(id = 1, companyId = companyId, name = "Do usunięcia", durationMinutes = 30, price = 50)
        every { offeringRepository.findById(1L) } returns Optional.of(existing)
        every { offeringRepository.save(any()) } answers { firstArg() }

        // WHEN
        offeringService.deleteOffering(1L, companyId)

        // THEN
        verify(exactly = 1) {
            offeringRepository.save(match { it.id == 1L && !it.active })
        }
        verify(exactly = 0) { offeringRepository.deleteById(any()) }
    }

    @Test
    fun `deleteOffering should throw when offering belongs to different company`() {
        // GIVEN
        val existing = Offering(id = 1, companyId = 999L, name = "Obca", durationMinutes = 30, price = 100)
        every { offeringRepository.findById(1L) } returns Optional.of(existing)

        // WHEN & THEN
        assertThrows<IllegalStateException> {
            offeringService.deleteOffering(1L, companyId)
        }
        verify(exactly = 0) { offeringRepository.save(any()) }
    }

    @Test
    fun `deleteOffering should throw when offering does not exist`() {
        // GIVEN
        every { offeringRepository.findById(999L) } returns Optional.empty()

        // WHEN & THEN
        assertThrows<IllegalArgumentException> {
            offeringService.deleteOffering(999L, companyId)
        }
    }

    @Test
    fun `uploadImage should delegate to offeringImageService and return offering`() {
        // GIVEN
        val offering = Offering(id = 1, companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200)
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(100))
        every { offeringRepository.findById(1L) } returns Optional.of(offering)
        every { offeringImageService.upload(companyId, 1L, file) } returns
                OfferingImage(id = 10, offeringId = 1L, imageUrl = "http://cdn/img.jpg")

        // WHEN
        val result = offeringService.uploadImage(1L, companyId, file)

        // THEN
        assertEquals(offering, result)
        verify(exactly = 1) { offeringImageService.upload(companyId, 1L, file) }
    }

    @Test
    fun `uploadImage should throw when offering belongs to different company`() {
        // GIVEN
        val offering = Offering(id = 1, companyId = 999L, name = "Obca", durationMinutes = 30, price = 100)
        val file = MockMultipartFile("image", "photo.jpg", "image/jpeg", ByteArray(100))
        every { offeringRepository.findById(1L) } returns Optional.of(offering)

        // WHEN & THEN
        assertThrows<IllegalStateException> {
            offeringService.uploadImage(1L, companyId, file)
        }
        verify(exactly = 0) { offeringImageService.upload(any(), any(), any()) }
    }

    @Test
    fun `deleteImage should delegate to offeringImageService and return offering`() {
        // GIVEN
        val offering = Offering(id = 1, companyId = companyId, name = "Masaż", durationMinutes = 60, price = 200)
        every { offeringRepository.findById(1L) } returns Optional.of(offering)
        every { offeringImageService.delete(42L, 1L) } returns Unit

        // WHEN
        val result = offeringService.deleteImage(1L, 42L, companyId)

        // THEN
        assertEquals(offering, result)
        verify(exactly = 1) { offeringImageService.delete(42L, 1L) }
    }

    @Test
    fun `deleteImage should throw when offering belongs to different company`() {
        // GIVEN
        val offering = Offering(id = 1, companyId = 999L, name = "Obca", durationMinutes = 30, price = 100)
        every { offeringRepository.findById(1L) } returns Optional.of(offering)

        // WHEN & THEN
        assertThrows<IllegalStateException> {
            offeringService.deleteImage(1L, 42L, companyId)
        }
        verify(exactly = 0) { offeringImageService.delete(any(), any()) }
    }
}
