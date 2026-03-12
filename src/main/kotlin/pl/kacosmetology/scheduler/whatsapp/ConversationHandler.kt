package pl.kacosmetology.scheduler.whatsapp

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.kacosmetology.scheduler.availability.AvailabilityService
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.employeeoffering.EmployeeOfferingAssignmentRepository
import pl.kacosmetology.scheduler.offering.OfferingService
import pl.kacosmetology.scheduler.reservation.ReservationService
import pl.kacosmetology.scheduler.user.UserRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Stateful conversation handler for the WhatsApp booking bot.
 *
 * Each incoming message advances the conversation through [ConversationStep] states stored in Redis via [ConversationStore].
 * Business logic is delegated to existing services; this class only orchestrates the dialogue.
 */
@Service
class ConversationHandler(
    private val sender: WhatsAppSender,
    private val store: ConversationStore,
    private val properties: WhatsAppProperties,
    private val offeringService: OfferingService,
    private val availabilityService: AvailabilityService,
    private val reservationService: ReservationService,
    private val userRepository: UserRepository,
    private val companyEmployeeRepository: CompanyEmployeeRepository,
    private val assignmentRepository: EmployeeOfferingAssignmentRepository
) {

    private val logger = LoggerFactory.getLogger(ConversationHandler::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Entry point for every inbound WhatsApp message.
     *
     * @param phone raw sender phone from Meta (e.g. `"48123456789"`)
     * @param text  raw message body
     */
    fun handle(phone: String, text: String) {
        val normalized = normalizePhone(phone)
        val state = store.get(normalized)
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        if (lower == "anuluj" || lower == "cancel") {
            store.delete(normalized)
            sender.sendMessage(normalized, "Anulowano. Wpisz cokolwiek, aby rozpocząć od nowa.")
            return
        }

        when (state.step) {
            ConversationStep.IDLE -> startFlow(normalized)
            ConversationStep.SELECTING_SERVICE -> handleSelectingService(normalized, state, trimmed)
            ConversationStep.SELECTING_EMPLOYEE -> handleSelectingEmployee(normalized, state, trimmed)
            ConversationStep.SELECTING_DATE -> handleSelectingDate(normalized, state, trimmed)
            ConversationStep.SELECTING_TIME -> handleSelectingTime(normalized, state, trimmed)
            ConversationStep.CONFIRMING -> handleConfirming(normalized, state, lower)
            ConversationStep.AWAITING_FIRST_NAME -> handleAwaitingFirstName(normalized, state, trimmed)
            ConversationStep.AWAITING_LAST_NAME -> handleAwaitingLastName(normalized, state, trimmed)
        }
    }

    // -------------------------------------------------------------------------
    // Step handlers
    // -------------------------------------------------------------------------

    private fun startFlow(phone: String) {
        val offerings = offeringService.getCompanyOfferings(properties.companyId)
        if (offerings.isEmpty()) {
            sender.sendMessage(phone, "Przepraszamy, aktualnie nie ma dostępnych usług. Spróbuj ponownie później.")
            return
        }
        val sb = StringBuilder("Witaj! Wybierz usługę (wpisz numer):\n")
        offerings.forEachIndexed { i, offering ->
            sb.append("\n${i + 1}. ${offering.name} (${offering.durationMinutes} min) - ${offering.price} zł")
        }
        val newState = ConversationState(
            step = ConversationStep.SELECTING_SERVICE,
            serviceOptions = offerings.map { it.id!! }
        )
        store.save(phone, newState)
        sender.sendMessage(phone, sb.toString())
    }

    private fun handleSelectingService(phone: String, state: ConversationState, text: String) {
        val index = parseIndex(text, state.serviceOptions.size)
        if (index == null) {
            sender.sendMessage(phone, "Wpisz numer od 1 do ${state.serviceOptions.size}.")
            return
        }
        val offeringId = state.serviceOptions[index]
        val offering = offeringService.getOfferingById(offeringId)

        val companyEmployees = companyEmployeeRepository.findAllByCompanyId(properties.companyId)
        val eligibleUsers = companyEmployees
            .filter { emp ->
                !assignmentRepository.existsByEmployeeId(emp.userId) ||
                        assignmentRepository.existsByEmployeeIdAndOfferingId(emp.userId, offeringId)
            }
            .mapNotNull { emp ->
                userRepository.findById(emp.userId).orElse(null)?.let { user -> Pair(emp.userId, user) }
            }

        if (eligibleUsers.isEmpty()) {
            sender.sendMessage(phone, "Brak dostępnych pracowników dla tej usługi. Wybierz inną usługę.")
            startFlow(phone)
            return
        }

        val sb = StringBuilder("Wybierz pracownika:\n")
        eligibleUsers.forEachIndexed { i, (_, user) ->
            sb.append("\n${i + 1}. ${user.firstName} ${user.lastName.first()}.")
        }
        val newState = state.copy(
            step = ConversationStep.SELECTING_EMPLOYEE,
            serviceId = offeringId,
            serviceName = offering.name,
            employeeOptions = eligibleUsers.map { it.first }
        )
        store.save(phone, newState)
        sender.sendMessage(phone, sb.toString())
    }

    private fun handleSelectingEmployee(phone: String, state: ConversationState, text: String) {
        val index = parseIndex(text, state.employeeOptions.size)
        if (index == null) {
            sender.sendMessage(phone, "Wpisz numer od 1 do ${state.employeeOptions.size}.")
            return
        }
        val employeeId = state.employeeOptions[index]
        val user = userRepository.findById(employeeId).orElse(null)
        val employeeName = user?.let { "${it.firstName} ${it.lastName.first()}." } ?: "Pracownik"

        val offeringId = state.serviceId ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val availableDates = findAvailableDates(employeeId, offeringId)
        if (availableDates.isEmpty()) {
            sender.sendMessage(
                phone,
                "Brak wolnych terminów w ciągu najbliższych 7 dni dla wybranego pracownika. Wybierz innego pracownika."
            )
            resendEmployeeList(phone, state)
            return
        }

        val sb = StringBuilder("Wybierz datę:\n")
        availableDates.forEachIndexed { i, dateStr ->
            sb.append("\n${i + 1}. ${formatPolishDate(LocalDate.parse(dateStr, dateFormatter))}")
        }
        val newState = state.copy(
            step = ConversationStep.SELECTING_DATE,
            employeeId = employeeId,
            employeeName = employeeName,
            dateOptions = availableDates
        )
        store.save(phone, newState)
        sender.sendMessage(phone, sb.toString())
    }

    private fun handleSelectingDate(phone: String, state: ConversationState, text: String) {
        val index = parseIndex(text, state.dateOptions.size)
        if (index == null) {
            sender.sendMessage(phone, "Wpisz numer od 1 do ${state.dateOptions.size}.")
            return
        }
        val dateStr = state.dateOptions[index]
        val date = LocalDate.parse(dateStr, dateFormatter)

        val employeeId = state.employeeId ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val serviceId = state.serviceId ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val slots = try {
            availabilityService.getAvailableSlots(employeeId, serviceId, date)
        } catch (e: Exception) {
            logger.warn("Failed to get slots for employee={} service={} date={}", employeeId, serviceId, dateStr, e)
            emptyList()
        }

        if (slots.isEmpty()) {
            sender.sendMessage(phone, "Brak wolnych godzin w tym dniu. Wybierz inną datę.")
            return
        }

        val sb = StringBuilder("Dostępne godziny:\n")
        slots.forEachIndexed { i, slot ->
            sb.append("\n${i + 1}. ${slot.time.format(timeFormatter)}")
        }
        val newState = state.copy(
            step = ConversationStep.SELECTING_TIME,
            date = date,
            timeOptions = slots.map { it.time.format(timeFormatter) }
        )
        store.save(phone, newState)
        sender.sendMessage(phone, sb.toString())
    }

    private fun handleSelectingTime(phone: String, state: ConversationState, text: String) {
        val index = parseIndex(text, state.timeOptions.size)
        if (index == null) {
            sender.sendMessage(phone, "Wpisz numer od 1 do ${state.timeOptions.size}.")
            return
        }
        val timeStr = state.timeOptions[index]
        val date = state.date ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val time = LocalTime.parse(timeStr, timeFormatter)

        val summary = buildSummary(state, date, time)
        val newState = state.copy(
            step = ConversationStep.CONFIRMING,
            time = time
        )
        store.save(phone, newState)
        sender.sendMessage(phone, "$summary\n\nWpisz \"tak\" aby potwierdzić lub \"nie\" aby anulować.")
    }

    private fun handleConfirming(phone: String, state: ConversationState, lower: String) {
        when (lower) {
            "tak" -> {
                val existingUser = userRepository.findByPhoneNumber(phone)
                if (existingUser != null) {
                    createReservation(phone, state, existingUser.firstName, existingUser.lastName)
                } else {
                    store.save(phone, state.copy(step = ConversationStep.AWAITING_FIRST_NAME))
                    sender.sendMessage(phone, "Podaj swoje imię:")
                }
            }

            "nie" -> {
                store.delete(phone)
                sender.sendMessage(phone, "Rezerwacja anulowana. Wpisz cokolwiek, aby rozpocząć od nowa.")
            }

            else -> {
                sender.sendMessage(phone, "Wpisz \"tak\" aby potwierdzić lub \"nie\" aby anulować.")
            }
        }
    }

    private fun handleAwaitingFirstName(phone: String, state: ConversationState, text: String) {
        val newState = state.copy(
            step = ConversationStep.AWAITING_LAST_NAME,
            pendingFirstName = text
        )
        store.save(phone, newState)
        sender.sendMessage(phone, "Podaj swoje nazwisko:")
    }

    private fun handleAwaitingLastName(phone: String, state: ConversationState, text: String) {
        createReservation(phone, state, state.pendingFirstName!!, text)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createReservation(phone: String, state: ConversationState, firstName: String, lastName: String) {
        val date = state.date ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val time = state.time ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val employeeId = state.employeeId ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val serviceId = state.serviceId ?: run {
            sender.sendMessage(phone, "Coś poszło nie tak. Zacznijmy od nowa.")
            store.delete(phone)
            return
        }
        val startTime = LocalDateTime.of(date, time)

        try {
            val reservation = reservationService.createReservationByStaff(
                employeeId = employeeId,
                serviceId = serviceId,
                startTime = startTime,
                customerPhone = phone,
                customerFirstName = firstName,
                customerLastName = lastName
            )
            store.delete(phone)
            sender.sendMessage(
                phone,
                "✅ Rezerwacja potwierdzona! Nr: #${reservation.id}\n" +
                        "Usługa: ${state.serviceName}\n" +
                        "Pracownik: ${state.employeeName}\n" +
                        "Termin: ${formatPolishDate(date)}, ${time.format(timeFormatter)}\n\n" +
                        "Do zobaczenia!"
            )
        } catch (e: IllegalStateException) {
            logger.warn("Slot already taken for phone={}: {}", phone, e.message)
            store.save(phone, state.copy(step = ConversationStep.SELECTING_DATE))
            val dateListMsg = buildDateSelectionMessage(state)
            sender.sendMessage(
                phone,
                "❌ Przepraszamy, ten termin jest już zajęty. Wybierz inny termin.\n\n$dateListMsg"
            )
        } catch (e: Exception) {
            logger.error("Unexpected error creating reservation for phone={}", phone, e)
            store.delete(phone)
            sender.sendMessage(phone, "❌ Wystąpił nieoczekiwany błąd. Spróbuj ponownie.")
        }
    }

    /** Resends the employee selection list for the current state (used when no dates are available). */
    private fun resendEmployeeList(phone: String, state: ConversationState) {
        val sb = StringBuilder("Wybierz pracownika:\n")
        state.employeeOptions.forEachIndexed { i, empId ->
            val empUser = userRepository.findById(empId).orElse(null)
            sb.append("\n${i + 1}. ${empUser?.let { "${it.firstName} ${it.lastName.first()}." } ?: "Pracownik"}")
        }
        sender.sendMessage(phone, sb.toString())
    }

    /** Returns available date strings for the next 7 calendar days for the given employee + offering. */
    private fun findAvailableDates(employeeId: Long, offeringId: Long): List<String> {
        val today = LocalDate.now()
        return (0..6).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            try {
                val slots = availabilityService.getAvailableSlots(employeeId, offeringId, date)
                if (slots.isNotEmpty()) date.format(dateFormatter) else null
            } catch (e: Exception) {
                logger.warn(
                    "Error fetching slots for employee={} service={} date={}: {}",
                    employeeId,
                    offeringId,
                    date,
                    e.message
                )
                null
            }
        }
    }

    /** Parses a 1-based index from user input. Returns null if not a valid number in [1, maxSize]. */
    private fun parseIndex(text: String, maxSize: Int): Int? {
        val n = text.toIntOrNull() ?: return null
        if (n < 1 || n > maxSize) return null
        return n - 1
    }

    /** Normalises the phone number to E.164 format by prepending `+` if missing. */
    private fun normalizePhone(phone: String): String =
        if (phone.startsWith("+")) phone else "+$phone"

    private fun formatPolishDate(date: LocalDate): String {
        val day = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Pn"
            DayOfWeek.TUESDAY -> "Wt"
            DayOfWeek.WEDNESDAY -> "Śr"
            DayOfWeek.THURSDAY -> "Cz"
            DayOfWeek.FRIDAY -> "Pt"
            DayOfWeek.SATURDAY -> "Sb"
            DayOfWeek.SUNDAY -> "Nd"
        }
        val month = when (date.monthValue) {
            1 -> "stycznia"; 2 -> "lutego"; 3 -> "marca"; 4 -> "kwietnia"
            5 -> "maja"; 6 -> "czerwca"; 7 -> "lipca"; 8 -> "sierpnia"
            9 -> "września"; 10 -> "października"; 11 -> "listopada"; 12 -> "grudnia"
            else -> ""
        }
        return "$day, ${date.dayOfMonth.toString().padStart(2, '0')} $month"
    }

    private fun buildSummary(state: ConversationState, date: LocalDate, time: LocalTime): String =
        "Podsumowanie rezerwacji:\n" +
                "Usługa: ${state.serviceName}\n" +
                "Pracownik: ${state.employeeName}\n" +
                "Termin: ${formatPolishDate(date)}, ${time.format(timeFormatter)}"

    private fun buildDateSelectionMessage(state: ConversationState): String {
        val sb = StringBuilder("Wybierz datę:\n")
        state.dateOptions.forEachIndexed { i, dateStr ->
            sb.append("\n${i + 1}. ${formatPolishDate(LocalDate.parse(dateStr, dateFormatter))}")
        }
        return sb.toString()
    }
}
