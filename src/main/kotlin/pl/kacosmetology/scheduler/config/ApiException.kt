package pl.kacosmetology.scheduler.config

import org.springframework.http.HttpStatus

/**
 * Stable, machine-readable error codes returned in the `code` field of error responses. Names are part of the public
 * API contract; do not rename them.
 */
enum class ErrorCode(val status: HttpStatus) {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_LOCAL_TIME(HttpStatus.BAD_REQUEST),
    OFFERING_UNAVAILABLE(HttpStatus.CONFLICT),
    EMPLOYEE_NOT_IN_COMPANY(HttpStatus.BAD_REQUEST),
    EMPLOYEE_OFFERING_NOT_ASSIGNED(HttpStatus.CONFLICT),
    CUSTOMER_BLOCKED(HttpStatus.CONFLICT),
    BOOKING_ADVANCE_REQUIRED(HttpStatus.CONFLICT),
    CUSTOMER_PHONE_ALREADY_EXISTS(HttpStatus.CONFLICT),
    CUSTOMER_PROFILE_CHANGED(HttpStatus.CONFLICT),
    CUSTOMER_NAME_REQUIRED(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND),
    SCHEDULE_BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    SESSION_CONTEXT_REQUIRED(HttpStatus.BAD_REQUEST),
    SESSION_CONTEXT_CHANGED(HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT),
    QUOTE_RECONFIRMATION_REQUIRED(HttpStatus.CONFLICT),
    BOOKING_LIMIT_REACHED(HttpStatus.CONFLICT),
    BOOKING_WINDOW_EXCEEDED(HttpStatus.CONFLICT),
    REVISION_REQUIRED(HttpStatus.BAD_REQUEST),
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    DATA_CONFLICT(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
}

/**
 * Business error with a stable [code]. [RestExceptionHandler] renders it as a ProblemDetail with the code's HTTP
 * status, [message] as `detail`, and the code name as `code`.
 */
class ApiException(val code: ErrorCode, message: String, val extensions: Map<String, Any> = emptyMap()) :
    RuntimeException(message)
