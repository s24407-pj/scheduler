package pl.kacosmetology.scheduler.config

import java.sql.SQLException
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import pl.kacosmetology.scheduler.auth.RateLimitExceededException

/** PostgreSQL SQLSTATE for an exclusion-constraint violation (overlapping time ranges). */
private const val EXCLUSION_VIOLATION = "23P01"

/**
 * Renders every error as an RFC 9457 ProblemDetail (`application/problem+json`) with a stable `code` property.
 * Validation failures additionally carry `errors` (field name → message). Standard Spring MVC exceptions are handled by
 * [ResponseEntityExceptionHandler] and receive a code in [handleExceptionInternal].
 */
@RestControllerAdvice
class RestExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(RestExceptionHandler::class.java)

    /** Maps a coded business error to its status and code. */
    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ProblemDetail> =
        problem(ex.code, ex.message ?: ex.code.name)

    /**
     * Maps a database constraint violation to 409; exclusion-constraint overlaps become [ErrorCode.SLOT_UNAVAILABLE].
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ProblemDetail> =
        if (ex.sqlState() == EXCLUSION_VIOLATION) {
            problem(ErrorCode.SLOT_UNAVAILABLE, "Ten termin jest już zajęty")
        } else {
            log.warn("Data integrity violation: sqlState={}, constraint={}", ex.sqlState(), ex.constraintName())
            problem(ErrorCode.DATA_CONFLICT, "Operacja narusza spójność danych")
        }

    /** Maps a concurrent update detected by `@Version` to 409. */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLockingFailure(ex: OptimisticLockingFailureException): ResponseEntity<ProblemDetail> =
        problem(ErrorCode.CONCURRENT_MODIFICATION, "Dane zostały zmienione w międzyczasie, spróbuj ponownie")

    /** Wrong staff email or password. */
    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(ex: BadCredentialsException): ResponseEntity<ProblemDetail> =
        problem(ErrorCode.INVALID_CREDENTIALS, "Nieprawidłowe dane logowania")

    /** Missing or invalid session; reached through the security entry point configured in `SecurityConfig`. */
    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException): ResponseEntity<ProblemDetail> =
        problem(ErrorCode.UNAUTHORIZED, "Wymagane uwierzytelnienie")

    /** Authenticated caller lacks the required role. */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ProblemDetail> =
        problem(ErrorCode.ACCESS_DENIED, "Brak dostępu")

    /** OTP or login rate limit exceeded. */
    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimit(ex: RateLimitExceededException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(429)
            .header("Retry-After", ex.retryAfterSeconds.coerceAtLeast(1).toString())
            .body(
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.message ?: "Zbyt wiele żądań")
                    .coded(ErrorCode.RATE_LIMITED),
            )

    /** Fallback for uncoded business rule violations. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> =
        problem(ErrorCode.BAD_REQUEST, ex.message ?: "Nieprawidłowe żądanie")

    /** Fallback for uncoded state conflicts. */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ProblemDetail> {
        log.warn("Uncoded IllegalStateException: {}", ex.message)
        return problem(ErrorCode.CONFLICT, ex.message ?: "Konflikt")
    }

    /** Fallback for uncoded missing entities. */
    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ProblemDetail> =
        problem(ErrorCode.NOT_FOUND, ex.message ?: "Nie znaleziono")

    /** Anything unexpected: logged, details hidden from the client. */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled exception: ${ex.message}", ex)
        return problem(ErrorCode.INTERNAL_ERROR, "Wewnętrzny błąd serwera")
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val body = ex.body
        body.setProperty("errors", ex.bindingResult.fieldErrors.associate { it.field to it.defaultMessage })
        return handleExceptionInternal(ex, body, headers, status, request)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = body as? ProblemDetail ?: ProblemDetail.forStatus(statusCode)
        when (ex) {
            is MethodArgumentNotValidException -> problem.coded(ErrorCode.VALIDATION_FAILED, "Błąd walidacji")
            is HttpMessageNotReadableException ->
                problem.coded(ErrorCode.MALFORMED_REQUEST, "Nieprawidłowy format żądania")
            is MissingServletRequestParameterException ->
                problem.coded(ErrorCode.INVALID_PARAMETER, "Brak wymaganego parametru '${ex.parameterName}'")
            is TypeMismatchException ->
                problem.coded(ErrorCode.INVALID_PARAMETER, "Nieprawidłowa wartość parametru '${ex.propertyName}'")
            else ->
                if (problem.properties?.containsKey("code") != true) {
                    problem.setProperty("code", fallbackCode(statusCode))
                }
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun problem(code: ErrorCode, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(code.status).body(ProblemDetail.forStatusAndDetail(code.status, detail).coded(code))

    private fun ProblemDetail.coded(code: ErrorCode, detail: String? = null): ProblemDetail = apply {
        if (detail != null) this.detail = detail
        setProperty("code", code.name)
    }

    private fun fallbackCode(status: HttpStatusCode): String =
        when (status.value()) {
            401 -> ErrorCode.UNAUTHORIZED.name
            403 -> ErrorCode.ACCESS_DENIED.name
            429 -> ErrorCode.RATE_LIMITED.name
            500 -> ErrorCode.INTERNAL_ERROR.name
            else -> HttpStatus.resolve(status.value())?.name ?: "ERROR"
        }

    private fun DataIntegrityViolationException.sqlState(): String? =
        generateSequence(cause) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun DataIntegrityViolationException.constraintName(): String? =
        generateSequence(cause) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.constraintName
}
