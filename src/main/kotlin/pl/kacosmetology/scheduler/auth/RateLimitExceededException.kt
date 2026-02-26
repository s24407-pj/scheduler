package pl.kacosmetology.scheduler.auth

class RateLimitExceededException(message: String = "Zbyt wiele prób. Spróbuj ponownie za kilka minut.") :
    RuntimeException(message)

