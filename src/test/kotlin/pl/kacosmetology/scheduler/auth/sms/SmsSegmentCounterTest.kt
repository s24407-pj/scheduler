package pl.kacosmetology.scheduler.auth.sms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Boundary checks for single/multipart messages, GSM escape pairs and UTF-16 characters. */
class SmsSegmentCounterTest {
    @Test
    fun `GSM7 uses 160 septets for one part and 153 for multipart`() {
        for ((size, segments) in listOf(0 to 0, 160 to 1, 161 to 2, 306 to 2, 307 to 3)) {
            assertEquals(SmsLength(SmsEncoding.GSM7, size, segments), SmsSegmentCounter.count("a".repeat(size)))
        }
    }

    @Test
    fun `UCS2 uses 70 units for one part and 67 for multipart`() {
        for ((size, segments) in listOf(70 to 1, 71 to 2, 134 to 2, 135 to 3)) {
            assertEquals(SmsLength(SmsEncoding.UCS2, size, segments), SmsSegmentCounter.count("ą".repeat(size)))
        }
        assertEquals(SmsLength(SmsEncoding.UCS2, 71, 2), SmsSegmentCounter.count("a".repeat(70) + "—"))
    }

    @Test
    fun `all GSM extension symbols cost two septets without forcing UCS2`() {
        assertEquals(SmsLength(SmsEncoding.GSM7, 20, 1), SmsSegmentCounter.count("\u000c^{}\\[~]|€"))
        assertEquals(SmsLength(SmsEncoding.GSM7, 160, 1), SmsSegmentCounter.count("^".repeat(80)))
        assertEquals(SmsLength(SmsEncoding.GSM7, 162, 2), SmsSegmentCounter.count("^".repeat(81)))
        assertEquals(SmsLength(SmsEncoding.GSM7, 306, 3), SmsSegmentCounter.count("^".repeat(153)))
    }

    @Test
    fun `supplementary unicode uses two units and pairs stay within a segment`() {
        assertEquals(SmsLength(SmsEncoding.UCS2, 70, 1), SmsSegmentCounter.count("😀".repeat(35)))
        assertEquals(SmsLength(SmsEncoding.UCS2, 72, 2), SmsSegmentCounter.count("😀".repeat(36)))
        assertEquals(SmsLength(SmsEncoding.UCS2, 134, 3), SmsSegmentCounter.count("😀".repeat(67)))
    }

    @Test
    fun `GSM accented letters and line breaks remain GSM but Polish letters require UCS2`() {
        val gsm = "£¥èéùìòÇØøÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉÄÖÑÜ§¿äöñüà\n\r"
        assertEquals(SmsLength(SmsEncoding.GSM7, gsm.length, 1), SmsSegmentCounter.count(gsm))
        assertEquals(SmsEncoding.UCS2, SmsSegmentCounter.count("ąćęłńóśźż").encoding)
    }
}
