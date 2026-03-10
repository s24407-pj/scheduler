package pl.kacosmetology.scheduler.whatsapp

import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.just
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import pl.kacosmetology.scheduler.availability.AvailabilityService
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.reservation.Reservation
import pl.kacosmetology.scheduler.reservation.ReservationService
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.offering.Offering
import pl.kacosmetology.scheduler.offering.OfferingService
import pl.kacosmetology.scheduler.user.User
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Optional

@ExtendWith(MockKExtension::class)
class ConversationHandlerTest {

    @MockK private lateinit var sender: WhatsAppSender
    @MockK private lateinit var store: ConversationStore
    @MockK private lateinit var properties: WhatsAppProperties
    @MockK private lateinit var offeringService: OfferingService
    @MockK private lateinit var availabilityService: AvailabilityService
    @MockK private lateinit var reservationService: ReservationService
    @MockK private lateinit var userRepository: UserRepository
    @MockK private lateinit var companyEmployeeRepository: CompanyEmployeeRepository
    @MockK private lateinit var assignmentRepository: EmployeeOfferingAssignmentRepository

    @InjectMockKs private lateinit var handler: ConversationHandler

    private val companyId = 1L
    private val serviceId = 10L
    private val employeeId = 20L
    private val phone = "48123456789"
    private val normalizedPhone = "+48123456789"

    private fun setupCommonMocks() {
        every { properties.companyId } returns companyId
        every { properties.verifyToken } returns "dev"
        every { sender.sendMessage(any(), any()) } just runs
        every { store.save(any(), any()) } just runs
        every { store.delete(any()) } just runs
    }

    @Test
    fun `IDLE state should send greeting and service list`() {
        setupCommonMocks()
        val service = Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
        every { store.get(normalizedPhone) } returns ConversationState(step = ConversationStep.IDLE)
        every { offeringService.getCompanyOfferings(companyId) } returns listOf(service)

        handler.handle(phone, "cześć")

        val msgSlot = slot<String>()
        verify { store.save(normalizedPhone, any()) }
        verify { sender.sendMessage(eq(normalizedPhone), capture(msgSlot)) }
        assert(msgSlot.captured.contains("Strzyżenie"))
        assert(msgSlot.captured.contains("30 min"))
        assert(msgSlot.captured.contains("80 zł"))
    }

    @Test
    fun `SELECTING_SERVICE with valid number should transition to SELECTING_EMPLOYEE`() {
        setupCommonMocks()
        val service = Offering(id = serviceId, companyId = companyId, name = "Strzyżenie", durationMinutes = 30, price = 80)
        val employee = CompanyEmployee(companyId = companyId, userId = employeeId, role = "EMPLOYEE")
        val user = User(id = employeeId, phoneNumber = "+48999000111", firstName = "Anna", lastName = "Kowalska")

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.SELECTING_SERVICE,
            serviceOptions = listOf(serviceId)
        )
        every { offeringService.getOfferingById(serviceId) } returns service
        every { companyEmployeeRepository.findAllByCompanyId(companyId) } returns listOf(employee)
        every { assignmentRepository.existsByEmployeeId(employeeId) } returns false
        every { userRepository.findById(employeeId) } returns Optional.of(user)

        handler.handle(phone, "1")

        val stateSlot = slot<ConversationState>()
        verify { store.save(normalizedPhone, capture(stateSlot)) }
        assert(stateSlot.captured.step == ConversationStep.SELECTING_EMPLOYEE)
        assert(stateSlot.captured.serviceId == serviceId)

        val msgSlot = slot<String>()
        verify { sender.sendMessage(eq(normalizedPhone), capture(msgSlot)) }
        assert(msgSlot.captured.contains("Anna K."))
    }

    @Test
    fun `SELECTING_SERVICE with invalid number should repeat the prompt`() {
        setupCommonMocks()
        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.SELECTING_SERVICE,
            serviceOptions = listOf(serviceId)
        )

        handler.handle(phone, "5")

        verify(exactly = 0) { store.save(any(), any()) }
        val msgSlot = slot<String>()
        verify { sender.sendMessage(eq(normalizedPhone), capture(msgSlot)) }
        assert(msgSlot.captured.contains("1"))
    }

    @Test
    fun `SELECTING_EMPLOYEE with valid number should transition to SELECTING_DATE`() {
        setupCommonMocks()
        val user = User(id = employeeId, phoneNumber = "+48999000111", firstName = "Anna", lastName = "Kowalska")
        val today = LocalDate.now()

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.SELECTING_EMPLOYEE,
            serviceId = serviceId,
            serviceOptions = listOf(serviceId),
            employeeOptions = listOf(employeeId)
        )
        every { userRepository.findById(employeeId) } returns Optional.of(user)
        every { availabilityService.getAvailableSlots(employeeId, serviceId, any()) } returns listOf(
            pl.kacosmetology.scheduler.availability.AvailableSlotResponse(LocalTime.of(10, 0), 100, 100)
        )

        handler.handle(phone, "1")

        val stateSlot = slot<ConversationState>()
        verify { store.save(normalizedPhone, capture(stateSlot)) }
        assert(stateSlot.captured.step == ConversationStep.SELECTING_DATE)
        assert(stateSlot.captured.employeeId == employeeId)
        assert(stateSlot.captured.dateOptions.isNotEmpty())
    }

    @Test
    fun `SELECTING_DATE with valid number should transition to SELECTING_TIME`() {
        setupCommonMocks()
        val today = LocalDate.now().plusDays(1)
        val dateStr = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.SELECTING_DATE,
            serviceId = serviceId,
            employeeId = employeeId,
            dateOptions = listOf(dateStr)
        )
        every { availabilityService.getAvailableSlots(employeeId, serviceId, today) } returns listOf(
            pl.kacosmetology.scheduler.availability.AvailableSlotResponse(LocalTime.of(9, 0), 100, 100),
            pl.kacosmetology.scheduler.availability.AvailableSlotResponse(LocalTime.of(9, 30), 100, 100)
        )

        handler.handle(phone, "1")

        val stateSlot = slot<ConversationState>()
        verify { store.save(normalizedPhone, capture(stateSlot)) }
        assert(stateSlot.captured.step == ConversationStep.SELECTING_TIME)
        assert(stateSlot.captured.date == today)
        assert(stateSlot.captured.timeOptions.size == 2)
    }

    @Test
    fun `SELECTING_TIME with valid number should show summary and transition to CONFIRMING`() {
        setupCommonMocks()
        val tomorrow = LocalDate.now().plusDays(1)

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.SELECTING_TIME,
            serviceId = serviceId,
            serviceName = "Strzyżenie",
            employeeId = employeeId,
            employeeName = "Anna K.",
            date = tomorrow,
            timeOptions = listOf("09:00", "09:30")
        )

        handler.handle(phone, "1")

        val stateSlot = slot<ConversationState>()
        verify { store.save(normalizedPhone, capture(stateSlot)) }
        assert(stateSlot.captured.step == ConversationStep.CONFIRMING)
        assert(stateSlot.captured.time == LocalTime.of(9, 0))

        val msgSlot = slot<String>()
        verify { sender.sendMessage(eq(normalizedPhone), capture(msgSlot)) }
        assert(msgSlot.captured.contains("Strzyżenie"))
        assert(msgSlot.captured.contains("Anna K."))
        assert(msgSlot.captured.contains("tak"))
    }

    @Test
    fun `CONFIRMING with 'tak' for existing customer should create reservation and reset`() {
        setupCommonMocks()
        val tomorrow = LocalDate.now().plusDays(1)
        val existingUser = User(id = 99L, phoneNumber = normalizedPhone, firstName = "Jan", lastName = "Kowalski")
        val reservation = Reservation(
            id = 42L, companyId = companyId, customerId = existingUser.id, employeeId = employeeId,
            serviceId = serviceId, price = 80, status = ReservationStatus.PENDING,
            startTime = LocalDateTime.of(tomorrow, LocalTime.of(9, 0)),
            endTime = LocalDateTime.of(tomorrow, LocalTime.of(9, 30))
        )

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.CONFIRMING,
            serviceId = serviceId,
            serviceName = "Strzyżenie",
            employeeId = employeeId,
            employeeName = "Anna K.",
            date = tomorrow,
            time = LocalTime.of(9, 0)
        )
        every { userRepository.findByPhoneNumber(normalizedPhone) } returns existingUser
        every { reservationService.createReservationByStaff(employeeId, serviceId, any(), normalizedPhone, "Jan", "Kowalski") } returns reservation

        handler.handle(phone, "tak")

        verify { store.delete(normalizedPhone) }
        val msgSlot = slot<String>()
        verify { sender.sendMessage(eq(normalizedPhone), capture(msgSlot)) }
        assert(msgSlot.captured.contains("#42"))
        assert(msgSlot.captured.contains("✅"))
    }

    @Test
    fun `CONFIRMING with 'tak' for new customer should transition to AWAITING_FIRST_NAME`() {
        setupCommonMocks()
        val tomorrow = LocalDate.now().plusDays(1)

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.CONFIRMING,
            serviceId = serviceId,
            date = tomorrow,
            time = LocalTime.of(9, 0)
        )
        every { userRepository.findByPhoneNumber(normalizedPhone) } returns null

        handler.handle(phone, "tak")

        val stateSlot = slot<ConversationState>()
        verify { store.save(normalizedPhone, capture(stateSlot)) }
        assert(stateSlot.captured.step == ConversationStep.AWAITING_FIRST_NAME)
        verify { sender.sendMessage(eq(normalizedPhone), any()) }
    }

    @Test
    fun `CONFIRMING with 'nie' should cancel and reset`() {
        setupCommonMocks()
        every { store.get(normalizedPhone) } returns ConversationState(step = ConversationStep.CONFIRMING)

        handler.handle(phone, "nie")

        verify { store.delete(normalizedPhone) }
        verify { sender.sendMessage(eq(normalizedPhone), any()) }
    }

    @Test
    fun `AWAITING_FIRST_NAME should save name and transition to AWAITING_LAST_NAME`() {
        setupCommonMocks()
        every { store.get(normalizedPhone) } returns ConversationState(step = ConversationStep.AWAITING_FIRST_NAME)

        handler.handle(phone, "Jan")

        val stateSlot = slot<ConversationState>()
        verify { store.save(normalizedPhone, capture(stateSlot)) }
        assert(stateSlot.captured.step == ConversationStep.AWAITING_LAST_NAME)
        assert(stateSlot.captured.pendingFirstName == "Jan")
        verify { sender.sendMessage(eq(normalizedPhone), any()) }
    }

    @Test
    fun `AWAITING_LAST_NAME should create reservation for new customer`() {
        setupCommonMocks()
        val tomorrow = LocalDate.now().plusDays(1)
        val reservation = Reservation(
            id = 55L, companyId = companyId, customerId = 77L, employeeId = employeeId,
            serviceId = serviceId, price = 80, status = ReservationStatus.PENDING,
            startTime = LocalDateTime.of(tomorrow, LocalTime.of(10, 0)),
            endTime = LocalDateTime.of(tomorrow, LocalTime.of(10, 30))
        )

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.AWAITING_LAST_NAME,
            serviceId = serviceId,
            serviceName = "Strzyżenie",
            employeeId = employeeId,
            employeeName = "Anna K.",
            date = tomorrow,
            time = LocalTime.of(10, 0),
            pendingFirstName = "Jan"
        )
        every { reservationService.createReservationByStaff(employeeId, serviceId, any(), normalizedPhone, "Jan", "Nowak") } returns reservation

        handler.handle(phone, "Nowak")

        verify { store.delete(normalizedPhone) }
        val msgSlot = slot<String>()
        verify { sender.sendMessage(eq(normalizedPhone), capture(msgSlot)) }
        assert(msgSlot.captured.contains("#55"))
    }

    @Test
    fun `anuluj command should reset conversation at any step`() {
        setupCommonMocks()
        every { store.get(normalizedPhone) } returns ConversationState(step = ConversationStep.SELECTING_DATE)

        handler.handle(phone, "anuluj")

        verify { store.delete(normalizedPhone) }
        verify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `IllegalStateException during reservation creation should go back to date selection`() {
        setupCommonMocks()
        val tomorrow = LocalDate.now().plusDays(1)
        val dateStr = tomorrow.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val existingUser = User(id = 99L, phoneNumber = normalizedPhone, firstName = "Jan", lastName = "Kowalski")

        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.CONFIRMING,
            serviceId = serviceId,
            serviceName = "Strzyżenie",
            employeeId = employeeId,
            employeeName = "Anna K.",
            date = tomorrow,
            time = LocalTime.of(9, 0),
            dateOptions = listOf(dateStr)
        )
        every { userRepository.findByPhoneNumber(normalizedPhone) } returns existingUser
        every {
            reservationService.createReservationByStaff(employeeId, serviceId, any(), normalizedPhone, "Jan", "Kowalski")
        } throws IllegalStateException("Ten termin jest już zajęty")

        handler.handle(phone, "tak")

        val stateSlot = slot<ConversationState>()
        verify { store.save(normalizedPhone, capture(stateSlot)) }
        assert(stateSlot.captured.step == ConversationStep.SELECTING_DATE)

        val msgSlot = slot<String>()
        verify { sender.sendMessage(eq(normalizedPhone), capture(msgSlot)) }
        assert(msgSlot.captured.contains("❌"))
    }

    @Test
    fun `SELECTING_SERVICE with non-numeric input should repeat the prompt`() {
        setupCommonMocks()
        every { store.get(normalizedPhone) } returns ConversationState(
            step = ConversationStep.SELECTING_SERVICE,
            serviceOptions = listOf(serviceId)
        )

        handler.handle(phone, "abcd")

        verify(exactly = 0) { store.save(any(), any()) }
        verify { sender.sendMessage(eq(normalizedPhone), any()) }
    }
}
