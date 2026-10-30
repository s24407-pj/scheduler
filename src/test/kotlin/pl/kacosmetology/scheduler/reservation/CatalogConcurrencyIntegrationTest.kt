package pl.kacosmetology.scheduler.reservation

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.transaction.support.TransactionTemplate

/** A lock wait must not leave pre-lock catalog entities in the booking persistence context. */
class CatalogConcurrencyIntegrationTest : ReservationContractTestSupport() {
    @Test
    fun `category assignment preserves catalog changes committed before its company lock`() {
        val category =
            jdbc.queryForObject(
                "INSERT INTO offering_categories(company_id,name) VALUES(?,'Nowa') RETURNING id",
                UUID::class.java,
                salon.company,
            )!!
        val cookie = staffCookie()
        val pool = Executors.newSingleThreadExecutor()
        var attempt: Future<MvcResult>? = null
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.queryForMap("SELECT id FROM companies WHERE id=? FOR UPDATE", salon.company)
                jdbc.update("UPDATE offerings SET active=false,price=777,duration_minutes=45 WHERE id=?", salon.service)
                attempt =
                    pool.submit<MvcResult> {
                        mvc.perform(
                                patch("/api/offerings/${salon.service}/category")
                                    .cookie(cookie)
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(json.writeValueAsString(mapOf("categoryId" to category))),
                            )
                            .andReturn()
                    }
                awaitCompanyWait(checkNotNull(attempt))
            }
            val result = checkNotNull(attempt).get(15, TimeUnit.SECONDS)
            assertEquals(204, result.response.status, result.response.contentAsString)
            val offering =
                jdbc.queryForMap(
                    "SELECT active,price,duration_minutes,category_id FROM offerings WHERE id=?",
                    salon.service,
                )
            assertEquals(false, offering["active"])
            assertEquals(777, offering["price"])
            assertEquals(45, offering["duration_minutes"])
            assertEquals(category, offering["category_id"])
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `booking observes catalog deactivation committed while waiting for company lock`() {
        val result = bookAfterCatalogCommit(active = false)
        assertEquals(409, result.response.status, result.response.contentAsString)
        assertEquals("OFFERING_UNAVAILABLE", tree(result)["code"].asString())
        assertEquals(0L, count("reservations"))
    }

    @Test
    fun `booking without quote accepts current price name and duration after lock wait`() {
        val result = bookAfterCatalogCommit(active = true)
        assertEquals(201, result.response.status, result.response.contentAsString)
        assertEquals(777, tree(result)["price"].asInt())
        assertEquals(45, tree(result)["durationMinutes"].asInt())
        assertEquals("Nowa usługa", tree(result)["serviceName"].asString())
    }

    @Test
    fun `expired quote requires reconfirmation against post-lock catalog terms`() {
        val quote = quote()
        setNow(initialNow.plusSeconds(600))
        val result = bookAfterCatalogCommit(active = true, quote = quote)
        assertEquals(409, result.response.status, result.response.contentAsString)
        assertEquals("QUOTE_RECONFIRMATION_REQUIRED", tree(result)["code"].asString())
    }

    @Test
    fun `unexpired quote still protects terms through concurrent deactivation`() {
        val result = bookAfterCatalogCommit(active = false, quote = quote())
        assertEquals(201, result.response.status, result.response.contentAsString)
        assertEquals(101, tree(result)["price"].asInt())
        assertEquals(30, tree(result)["durationMinutes"].asInt())
    }

    private fun quote(): UUID {
        val result =
            postJson(
                "/api/quotes",
                mapOf("serviceId" to salon.service, "startTime" to future.toString()),
                customerCookie(),
            )
        assertEquals(201, result.response.status, result.response.contentAsString)
        return UUID.fromString(tree(result)["quoteId"].asString())
    }

    private fun bookAfterCatalogCommit(active: Boolean, quote: UUID? = null): MvcResult {
        profile()
        shift()
        val cookie = customerCookie()
        val body = customerBody() - "firstVisitDetails" + ("quoteId" to quote)
        val pool = Executors.newSingleThreadExecutor()
        var attempt: Future<MvcResult>? = null
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.queryForMap("SELECT id FROM companies WHERE id=? FOR UPDATE", salon.company)
                jdbc.update(
                    "UPDATE offerings SET active=?,price=777,duration_minutes=45,name='Nowa usługa' WHERE id=?",
                    active,
                    salon.service,
                )
                attempt = pool.submit<MvcResult> { book(body = body, cookie = cookie) }
                awaitCompanyWait(checkNotNull(attempt))
            }
            return checkNotNull(attempt).get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    private fun awaitCompanyWait(attempt: Future<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            // A separate connection avoids PostgreSQL statistics snapshots cached by the locking transaction.
            checkNotNull(jdbc.dataSource).connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%companies%'",
                        )
                        .use {
                            it.next()
                            if (it.getInt(1) > 0) return
                        }
                }
            }
            if (attempt.isDone) fail<Unit>("Booking completed before the company lock was released: ${attempt.get()}")
            Thread.sleep(10)
        }
        fail<Unit>("Booking did not wait on the company lock")
    }
}
