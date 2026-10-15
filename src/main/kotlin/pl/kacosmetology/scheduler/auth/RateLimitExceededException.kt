package pl.kacosmetology.scheduler.auth

/** Authentication or booking rate limit, including the delay exposed through the Retry-After header. */
class RateLimitExceededException(
    message: String = "Zbyt wiele prób. Spróbuj ponownie za kilka minut.",
    val retryAfterSeconds: Long = 60,
) : RuntimeException(message)
