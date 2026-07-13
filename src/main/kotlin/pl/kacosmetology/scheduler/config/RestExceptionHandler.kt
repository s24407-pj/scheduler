package pl.kacosmetology.scheduler.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import pl.kacosmetology.scheduler.auth.RateLimitExceededException

/** Global exception handler that maps exceptions to standardized API error responses. */
@RestControllerAdvice
class RestExceptionHandler {

    private val logger = LoggerFactory.getLogger(RestExceptionHandler::class.java)

    data class ApiError(val message: String)
    data class ValidationError(val message: String, val errors: Map<String, String?>)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ValidationError> {
        val fieldErrors = ex.bindingResult.fieldErrors.associate { it.field to it.defaultMessage }
        return ResponseEntity.badRequest().body(ValidationError("Błąd walidacji", fieldErrors))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiError> {
        return ResponseEntity.badRequest().body(ApiError(ex.message ?: "Nieprawidłowe żądanie"))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError(ex.message ?: "Konflikt"))
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(ex.message ?: "Nie znaleziono"))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError("Brak dostępu"))
    }

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(ex: BadCredentialsException): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError("Nieprawidłowe dane logowania"))
    }

    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimit(ex: RateLimitExceededException): ResponseEntity<ApiError> {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError(ex.message ?: "Zbyt wiele żądań"))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedBody(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> {
        return ResponseEntity.badRequest().body(ApiError("Malformed request body"))
    }

    /** Maps malformed path and query parameter values to a client error. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiError> {
        return ResponseEntity.badRequest().body(ApiError("Invalid value for parameter '${ex.name}'"))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ApiError> {
        return ResponseEntity.status(ex.statusCode).body(ApiError(ex.reason ?: "Błąd"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ApiError> {
        logger.error("Unhandled exception: ${ex.message}", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError("Wewnętrzny błąd serwera"))
    }
}
