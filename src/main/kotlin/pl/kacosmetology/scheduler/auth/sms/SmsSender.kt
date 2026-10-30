package pl.kacosmetology.scheduler.auth.sms

/** Classifies provider outcomes for durable SMS delivery; an unknown outcome must never be retried blindly. */
interface SmsSender {
    /** Reports provider acceptance of an OTP; unclassified exceptions never prove a safe-to-retry rejection. */
    fun deliverOtp(phoneNumber: String, code: String): SmsDeliveryResult

    /**
     * Reports provider acceptance of an appointment message. Workers treat an unclassified thrown exception as
     * ambiguous, since a timeout does not prove rejection.
     */
    fun deliverMessage(phoneNumber: String, message: String): SmsDeliveryResult
}

/** Provider outcomes determine whether a durable delivery may be retried safely. */
enum class SmsDeliveryResult {
    ACCEPTED,
    DEFINITE_TEMPORARY_FAILURE,
    AMBIGUOUS,
}
