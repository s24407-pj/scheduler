package pl.kacosmetology.scheduler.user

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.Base64
import java.util.Locale
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import pl.kacosmetology.scheduler.company.CompanyEmployee
import pl.kacosmetology.scheduler.company.CompanyEmployeeRepository
import pl.kacosmetology.scheduler.config.ApiException
import pl.kacosmetology.scheduler.config.ErrorCode
import pl.kacosmetology.scheduler.reservation.ReservationRepository
import pl.kacosmetology.scheduler.reservation.ReservationStatus
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import pl.kacosmetology.scheduler.user.dto.*

/** Owner account administration and staff password lifecycle, isolated by company/account. */
@Service
class StaffAccountService(
    private val accounts: CompanyEmployeeRepository,
    private val companies: pl.kacosmetology.scheduler.company.CompanyRepository,
    private val links: StaffPasswordLinkRepository,
    private val passwords: PasswordEncoder,
    private val reservations: ReservationRepository,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
) {
    private val random = SecureRandom()

    /** Creates an EMPLOYEE account and initial one-use setup credential without a customer identity. */
    @Transactional
    fun create(companyId: UUID, request: CreateEmployeeRequest): EmployeeCreatedResponse {
        companies.findByIdForUpdate(companyId) ?: missing()
        val account =
            accounts.saveAndFlush(
                CompanyEmployee(
                    companyId = companyId,
                    userId = accounts.nextPublicId(),
                    role = "EMPLOYEE",
                    loginEmail = normalizeEmail(request.loginEmail),
                    firstName = request.firstName.trim(),
                    lastName = request.lastName.trim(),
                    contactPhone = request.contactPhone,
                ),
            )
        return EmployeeCreatedResponse(account.toStaffProfile(), replaceLink(account))
    }

    /** Edits only this company's employee profile and invalidates sessions when login identity changes. */
    @Transactional
    fun update(companyId: UUID, employeeId: UUID, request: UpdateEmployeeRequest): StaffProfileResponse {
        val account = employee(companyId, employeeId, allowOwner = true)
        val email = normalizeEmail(request.loginEmail)
        if (email != account.loginEmail) {
            account.loginEmail = email
            invalidate(account)
        }
        account.firstName = request.firstName.trim()
        account.lastName = request.lastName.trim()
        account.contactPhone = request.contactPhone
        return account.toStaffProfile()
    }

    /** Disabling revokes access/links immediately while preserving every appointment. */
    @Transactional
    fun setEnabled(companyId: UUID, employeeId: UUID, enabled: Boolean): EmployeeEnabledResponse {
        companies.findByIdForUpdate(companyId) ?: missing()
        val account = employee(companyId, employeeId)
        if (account.enabled != enabled) {
            account.enabled = enabled
            if (!enabled) {
                links.deleteById(requireNotNull(account.id))
                invalidate(account)
            }
        }
        val now = LocalDateTime.now(clock)
        val affected =
            if (enabled) emptyList()
            else
                reservations
                    .findEmployeeSchedule(companyId, employeeId, now, LocalDateTime.of(9999, 12, 31, 23, 59))
                    .filter {
                        it.startTime.isAfter(now) &&
                            it.status !in
                                setOf(
                                    ReservationStatus.CANCELLED,
                                    ReservationStatus.NO_SHOW,
                                    ReservationStatus.COMPLETED,
                                )
                    }
                    .map { AffectedEmployeeBooking(requireNotNull(it.id), it.startTime, it.endTime) }
        return EmployeeEnabledResponse(account.toStaffProfile(), affected)
    }

    /** Replaces setup/recovery link; disabled staff cannot acquire a new link. */
    @Transactional
    fun issueLink(companyId: UUID, employeeId: UUID): StaffPasswordLinkResponse =
        replaceLink(employee(companyId, employeeId))

    /** Validates current password directly and revokes every session without creating a new one. */
    @Transactional
    fun changePassword(accountId: UUID, request: ChangeStaffPasswordRequest) {
        val account = accounts.findAccountForUpdate(accountId) ?: missing()
        if (!account.enabled || !passwords.matches(request.currentPassword, account.passwordHash ?: "")) {
            throw BadCredentialsException("Nieprawidłowe aktualne hasło")
        }
        account.passwordHash = passwords.encode(request.newPassword)
        links.deleteById(accountId)
        invalidate(account)
    }

    /** Consumes a valid replacement-safe token once, sets password and invalidates all account sessions. */
    @Transactional
    fun setPassword(request: SetStaffPasswordRequest) {
        val hash = hash(request.token)
        val candidate = links.findByTokenHash(hash) ?: invalidLink()
        val account = accounts.findAccountForUpdate(candidate.staffAccountId) ?: invalidLink()
        val current = links.findByTokenHash(hash) ?: invalidLink()
        if (!account.enabled || !clock.instant().isBefore(current.expiresAt)) invalidLink()
        account.passwordHash = passwords.encode(request.newPassword)
        links.delete(current)
        invalidate(account)
    }

    /** Reads a staff member's current company-local profile. */
    @Transactional(readOnly = true)
    fun getOwn(accountId: UUID): StaffProfileResponse =
        accounts.findById(accountId).orElseThrow { missing() }.toStaffProfile()

    private fun employee(companyId: UUID, employeeId: UUID, allowOwner: Boolean = false): CompanyEmployee {
        val account = accounts.findByCompanyIdAndUserIdForUpdate(companyId, employeeId) ?: missing()
        if (account.role != "EMPLOYEE" && !(allowOwner && account.role == "OWNER"))
            throw ApiException(ErrorCode.ACCESS_DENIED, "Konto właściciela obsługuje operator")
        return account
    }

    private fun replaceLink(account: CompanyEmployee): StaffPasswordLinkResponse {
        if (!account.enabled) throw ApiException(ErrorCode.CONFLICT, "Konto pracownika jest wyłączone")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val expiry = clock.instant().plus(Duration.ofHours(24))
        val id = requireNotNull(account.id)
        val link = links.findById(id).orElse(null)
        if (link == null) links.save(StaffPasswordLink(id, hash(token), expiry))
        else {
            link.tokenHash = hash(token)
            link.expiresAt = expiry
        }
        return StaffPasswordLinkResponse(token, expiry)
    }

    private fun invalidate(account: CompanyEmployee) {
        account.authVersion++
        events.publishEvent(StaffAccountRevoked(requireNotNull(account.id)))
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun invalidLink(): Nothing =
        throw ApiException(ErrorCode.INVALID_CREDENTIALS, "Link jest nieprawidłowy lub wygasł")

    private fun missing(): Nothing = throw ApiException(ErrorCode.NOT_FOUND, "Pracownik nie istnieje")

    companion object {
        /** Canonical login address inside a company namespace. */
        fun normalizeEmail(value: String): String {
            val email = value.trim().lowercase(Locale.ROOT)
            if (!email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
                throw ApiException(ErrorCode.VALIDATION_FAILED, "Nieprawidłowy email")
            return email
        }
    }
}

/** Durable authVersion has already invalidated authentication when this after-commit cleanup runs. */
data class StaffAccountRevoked(val accountId: UUID)

/** Removes Redis sessions only after the credential transaction commits. */
@Component
class StaffSessionRevocationListener(private val sessions: AccountSessionRegistry) {
    private val log = LoggerFactory.getLogger(StaffSessionRevocationListener::class.java)

    /** Redis cleanup failure cannot restore the old DB credential version or roll back a password change. */
    @TransactionalEventListener
    fun revoke(event: StaffAccountRevoked) {
        runCatching { sessions.revokeAccount("staff:${event.accountId}") }
            .onFailure { log.warn("Staff session cleanup failed for account {}", event.accountId) }
    }
}
