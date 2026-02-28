package pl.kacosmetology.scheduler.auth.sms

/** Abstraction for sending OTP codes via SMS. */
interface SmsSender {
    /** Sends a one-time password [code] to the given [phoneNumber]. */
    fun sendOtp(phoneNumber: String, code: String)
}
