package pl.kacosmetology.scheduler

import java.util.TimeZone
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import pl.kacosmetology.scheduler.config.BUSINESS_ZONE

@SpringBootApplication class SchedulerApplication

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone(BUSINESS_ZONE))
    runApplication<SchedulerApplication>(*args)
}
