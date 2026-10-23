package pl.kacosmetology.scheduler.auth.sms

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import pl.kacosmetology.scheduler.config.SmsWorkerConfig
import pl.kacosmetology.scheduler.config.SmsWorkerProperties

/** Actual property binding rejects unsafe pool sizes before any sender or executor starts. */
class SmsWorkerConfigTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(SmsWorkerConfig::class.java)

    @Test
    fun `defaults isolate two OTP calls from four notification calls`() {
        runner.run { context ->
            assertNull(context.startupFailure)
            assertEquals(SmsWorkerProperties(2, 4), context.getBean<SmsWorkerProperties>())
        }
    }

    @Test
    fun `both worker limits reject zero negative and excessive concurrency`() {
        for (property in listOf("otp-concurrency", "notification-concurrency")) {
            for (invalid in listOf(-1, 0, 17)) {
                runner.withPropertyValues("sms.worker.$property=$invalid").run { context ->
                    assertNotNull(context.startupFailure)
                }
            }
        }
        runner.withPropertyValues("sms.worker.otp-concurrency=1", "sms.worker.notification-concurrency=16").run {
            context ->
            assertNull(context.startupFailure)
            assertEquals(SmsWorkerProperties(1, 16), context.getBean<SmsWorkerProperties>())
        }
    }
}
