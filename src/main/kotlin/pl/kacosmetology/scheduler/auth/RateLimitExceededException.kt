package pl.kacosmetology.scheduler.auth

/** Thrown when a phone number exceeds the allowed SMS OTP request rate. Maps to HTTP 429. */
class RateLimitExceededException(message: String = "Zbyt wiele prób. Spróbuj ponownie za kilka minut.") :
    RuntimeException(message)

