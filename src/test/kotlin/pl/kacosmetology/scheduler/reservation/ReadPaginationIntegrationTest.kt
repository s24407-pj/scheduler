package pl.kacosmetology.scheduler.reservation

import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
class ReadPaginationIntegrationTest : ReservationContractTestSupport() {
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `customer directory pages are stable bounded and company scoped`() {
        val ids = (1..5).map { newCustomer().also { profile(it) } }.toSet()
        newCustomer() // Pending identities must never enter the directory.
        fixtures.profile(fixtures.salon())
        val cookie = staffCookie()
        val responses =
            (0..2).map {
                mvc.perform(get("/api/customers").cookie(cookie).param("page", "$it").param("size", "2")).andReturn()
            }
        responses.forEach { assertEquals(200, it.response.status) }
        assertEquals(listOf(2, 2, 1), responses.map { tree(it).size() })
        assertEquals("1", responses[0].response.getHeader("X-Next-Page"))
        assertEquals("2", responses[1].response.getHeader("X-Next-Page"))
        assertNull(responses[2].response.getHeader("X-Next-Page"))
        assertNotNull(responses[0].response.getHeader("X-Page-Context"))
        assertEquals(1, responses.map { it.response.getHeader("X-Page-Context") }.toSet().size)
        val actual = responses.flatMap {
            tree(it).values().map { row -> java.util.UUID.fromString(row["id"].asString()) }
        }
        assertEquals(5, actual.distinct().size)
        assertEquals(ids, actual.toSet())
    }

    @Test
    fun `both histories page without archives or customer exposure of staff notes`() {
        val ids = (0..4).map { reservation(future.plusHours(it.toLong())) }.reversed()
        val archived = reservation(future.plusDays(1))
        jdbc.update("UPDATE reservations SET archived=true WHERE id=?", archived)
        jdbc.update("UPDATE reservations SET internal_notes='staff secret' WHERE company_id=?", salon.company)
        for ((path, cookie) in
            listOf(
                "/api/reservations/me" to customerCookie(),
                "/api/customers/${salon.customer}/visits" to staffCookie(),
            )) {
            val pages =
                (0..2).map { mvc.perform(get(path).cookie(cookie).param("page", "$it").param("size", "2")).andReturn() }
            pages.forEach { assertEquals(200, it.response.status) }
            assertEquals(
                ids.map { it.toString() },
                pages.flatMap {
                    tree(it).values().map { row -> row[if (path.endsWith("/me")) "id" else "reservationId"].asString() }
                },
            )
            assertNull(pages.last().response.getHeader("X-Next-Page"))
            if (path.endsWith("/me")) assertFalse(pages.any { it.response.contentAsString.contains("staff secret") })
            else assertTrue(pages.first().response.contentAsString.contains("staff secret"))
        }
        val foreign = fixtures.salon()
        assertEquals(
            404,
            mvc.perform(get("/api/customers/${foreign.customer}/visits").cookie(staffCookie()))
                .andReturn()
                .response
                .status,
        )
    }

    @Test
    fun `history presentation query count does not grow with reservations`() {
        repeat(25) { reservation(future.plusHours(it.toLong())) }
        val cookie = customerCookie()
        val stats = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        fun statements(size: Int): Long {
            stats.clear()
            val result = mvc.perform(get("/api/reservations/me").cookie(cookie).param("size", "$size")).andReturn()
            assertEquals(200, result.response.status)
            assertEquals(size, tree(result).size())
            return stats.prepareStatementCount
        }
        val small = statements(1)
        val large = statements(25)
        assertTrue(large <= small + 1, "Queries grew from $small to $large")
        assertTrue(large <= 8, "Unexpected query count $large")
    }

    @Test
    fun `invalid paging and excessive calendar ranges fail explicitly`() {
        val cookie = staffCookie()
        for ((name, value) in listOf("page" to "-1", "size" to "0", "size" to "201", "page" to "2147483647")) {
            assertEquals(
                400,
                mvc.perform(get("/api/customers").cookie(cookie).param(name, value)).andReturn().response.status,
            )
        }
        assertEquals(
            400,
            mvc.perform(
                    get("/api/reservations")
                        .cookie(cookie)
                        .param("employeeId", salon.employee.toString())
                        .param("start", future.toString())
                        .param("end", future.plusDays(94).toString()),
                )
                .andReturn()
                .response
                .status,
        )
    }
}
