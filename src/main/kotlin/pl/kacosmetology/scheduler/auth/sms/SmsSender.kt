package pl.kacosmetology.scheduler.auth.sms

/** Abstraction for sending SMS messages. */
interface SmsSender {
    /** Sends a one-time password [code] to the given [phoneNumber]. */
    fun sendOtp(phoneNumber: String, code: String)

    /** Provider acceptance for an OTP; unclassified exceptions never prove a safe-to-retry rejection. */
    fun deliverOtp(phoneNumber: String, code: String): SmsDeliveryResult =
        try {
            sendOtp(phoneNumber, code)
            SmsDeliveryResult.ACCEPTED
        } catch (_: Exception) {
            SmsDeliveryResult.AMBIGUOUS
        }

    /** Sends an arbitrary [message] to the given [phoneNumber]. */
    fun sendMessage(phoneNumber: String, message: String)

    /**
     * Categorizes provider acceptance. Modern adapters should override this method. An unclassified thrown exception is
     * ambiguous, since a timeout does not prove rejection; the legacy successful adapter implies acceptance.
     */
    fun deliverMessage(phoneNumber: String, message: String): SmsDeliveryResult =
        try {
            sendMessage(phoneNumber, message)
            SmsDeliveryResult.ACCEPTED
        } catch (_: Exception) {
            SmsDeliveryResult.AMBIGUOUS
        }
}

/** Provider outcomes determine whether a durable delivery may be retried safely. */
enum class SmsDeliveryResult {
    ACCEPTED,
    DEFINITE_TEMPORARY_FAILURE,
    AMBIGUOUS,
}
