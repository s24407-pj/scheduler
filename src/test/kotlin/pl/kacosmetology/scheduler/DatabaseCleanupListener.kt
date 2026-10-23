package pl.kacosmetology.scheduler

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener

/** Isolates PostgreSQL fixtures, including durable audit/submission/delivery children, before each test transaction. */
class DatabaseCleanupListener : AbstractTestExecutionListener() {
    override fun getOrder(): Int = 2500

    override fun beforeTestMethod(testContext: TestContext) {
        testContext.applicationContext
            .getBeansOfType(pl.kacosmetology.scheduler.config.SmsWorkers::class.java)
            .values
            .forEach { check(it.awaitIdle()) { "SMS workers did not settle before fixture cleanup" } }
        testContext.applicationContext
            .getBeansOfType(org.springframework.data.redis.core.StringRedisTemplate::class.java)
            .values
            .firstOrNull()
            ?.connectionFactory
            ?.connection
            ?.use { it.serverCommands().flushAll() }

        val jdbc =
            testContext.applicationContext.getBeansOfType(JdbcTemplate::class.java).values.firstOrNull() ?: return
        val tables =
            jdbc.queryForList<String>(
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'",
            )
        if (tables.isNotEmpty()) {
            jdbc.execute(
                "TRUNCATE TABLE " +
                    tables.filterNotNull().joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } +
                    " RESTART IDENTITY CASCADE",
            )
        }
    }
}
