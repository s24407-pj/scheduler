package pl.kacosmetology.scheduler.config

import io.micrometer.core.instrument.MeterRegistry
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import pl.kacosmetology.scheduler.auth.sms.SmsWork
import pl.kacosmetology.scheduler.auth.sms.SmsWorker

/** Independent provider concurrency limits; validation prevents unbounded allocation at startup. */
@Validated
@ConfigurationProperties("sms.worker")
data class SmsWorkerProperties(
    @field:Min(1) @field:Max(16) val otpConcurrency: Int = 2,
    @field:Min(1) @field:Max(16) val notificationConcurrency: Int = 4,
)

/** Registers validated SMS settings without replacing Boot's scheduler or application executor. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmsWorkerProperties::class)
class SmsWorkerConfig

/** Owns isolated SMS pools and stops them before Spring destroys repositories, Redis or the datasource. */
@Component
class SmsWorkers(
    private val properties: SmsWorkerProperties,
    private val registry: MeterRegistry,
    private val clock: Clock,
) : SmartLifecycle {
    private val workers = mutableListOf<SmsWorker>()
    private val kinds = mutableSetOf<String>()
    @Volatile private var running = false

    /** Creates a module-owned worker; callbacks preserve module boundaries and transaction ownership. */
    @Synchronized
    fun create(
        kind: String,
        source: (Set<String>, Int) -> List<SmsWork>,
        process: (UUID) -> Unit,
        pending: () -> Long,
    ): SmsWorker {
        require(kind in setOf("otp", "notification"))
        check(kinds.add(kind)) { "Each SMS kind must have exactly one worker pool" }
        val concurrency = if (kind == "otp") properties.otpConcurrency else properties.notificationConcurrency
        return SmsWorker(kind, concurrency, registry, clock, source, process, pending).also(workers::add)
    }

    /** Allows explicit waits in maintenance and integration tests without synchronous production dispatch. */
    fun awaitIdle(timeout: Duration = Duration.ofSeconds(10)): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        return workers.all { it.awaitIdle(Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))) }
    }

    /** Lifecycle startup does not dispatch work; each module must complete its own recovery first. */
    override fun start() {
        running = true
    }

    /** Stops all admission first, then waits at most fifteen seconds across both pools. */
    override fun stop() {
        workers.forEach { it.stopAdmission() }
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        try {
            workers.forEach { it.awaitTermination(deadline) }
        } finally {
            running = false
        }
    }

    /** Reports Spring lifecycle state, independently of per-module recovery readiness. */
    override fun isRunning(): Boolean = running

    /** Stop SMS before lower-phase infrastructure and before singleton destruction. */
    override fun getPhase(): Int = Int.MAX_VALUE
}
