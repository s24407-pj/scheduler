package pl.kacosmetology.scheduler.auth.sms

/** Abstraction for sending SMS messages. */
interface SmsSender {
    /** Sends a one-time password [code] to the given [phoneNumber]. */
    fun sendOtp(phoneNumber: String, code: String)

    /** Sends an arbitrary [message] to the given [phoneNumber]. */
    fun sendMessage(phoneNumber: String, message: String)
}
