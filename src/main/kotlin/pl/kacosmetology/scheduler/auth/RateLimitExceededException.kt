package pl.kacosmetology.scheduler.auth

/** Thrown when an authentication request or verification-attempt limit is exceeded. Maps to HTTP 429. */
class RateLimitExceededException(message: String = "Zbyt wiele prób. Spróbuj ponownie za kilka minut.") :
    RuntimeException(message)

