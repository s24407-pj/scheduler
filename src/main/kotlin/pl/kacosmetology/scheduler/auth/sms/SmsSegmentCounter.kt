package pl.kacosmetology.scheduler.auth.sms

/** Estimates standard GSM-7 or UCS-2 segmentation without changing the message or assuming transliteration. */
object SmsSegmentCounter {
    private val basic =
        ("@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà")
            .toSet()
    private val extended = "\u000c^{}\\[~]|€".toSet()

    /** Counts septets for GSM-7 and UTF-16 units for UCS-2, keeping escape pairs and surrogate pairs together. */
    fun count(content: String): SmsLength {
        val gsm = content.all { it in basic || it in extended }
        val encoding = if (gsm) SmsEncoding.GSM7 else SmsEncoding.UCS2
        val singleLimit = if (gsm) 160 else 70
        val multipartLimit = if (gsm) 153 else 67
        val widths =
            content.codePoints().toArray().map {
                if (gsm) (if (it.toChar() in extended) 2 else 1) else Character.charCount(it)
            }
        val units = widths.sum()
        if (units <= singleLimit) return SmsLength(encoding, units, if (units == 0) 0 else 1)
        var segments = 1
        var used = 0
        widths.forEach { width ->
            if (used + width > multipartLimit) {
                segments++
                used = 0
            }
            used += width
        }
        return SmsLength(encoding, units, segments)
    }
}

/** Alphabet chosen for a whole message; UCS-2 estimates count supplementary characters as two UTF-16 units. */
enum class SmsEncoding {
    GSM7,
    UCS2,
}

/** Estimated encoded units and billable segments; provider-specific transformations may change actual billing. */
data class SmsLength(val encoding: SmsEncoding, val units: Int, val segments: Int)
