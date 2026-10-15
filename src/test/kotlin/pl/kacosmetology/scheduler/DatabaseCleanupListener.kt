package pl.kacosmetology.scheduler

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestContext
import org.springframework.test.context.support.AbstractTestExecutionListener

/** Isolates PostgreSQL fixtures, including durable audit/submission/delivery children, before each test transaction. */
class DatabaseCleanupListener : AbstractTestExecutionListener() {
    override fun getOrder(): Int = 2500

    override fun beforeTestMethod(testContext: TestContext) {
        val jdbc =
            testContext.applicationContext.getBeansOfType(JdbcTemplate::class.java).values.firstOrNull() ?: return
        val tables =
            jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'",
                String::class.java,
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
