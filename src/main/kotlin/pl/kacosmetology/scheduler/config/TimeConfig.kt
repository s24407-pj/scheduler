package pl.kacosmetology.scheduler.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/** ID of the single business time zone; usable in annotations such as `@Scheduled(zone = ...)`. */
const val BUSINESS_ZONE_ID = "Europe/Warsaw"

/** Time zone of all salons. API date-times without an offset are wall-clock times in this zone. */
val BUSINESS_ZONE: ZoneId = ZoneId.of(BUSINESS_ZONE_ID)

/** Provides the application [Clock]; inject it instead of calling `now()` without a clock. */
@Configuration
class TimeConfig {

    /** System clock in [BUSINESS_ZONE]. */
    @Bean
    fun clock(): Clock = Clock.system(BUSINESS_ZONE)
}
