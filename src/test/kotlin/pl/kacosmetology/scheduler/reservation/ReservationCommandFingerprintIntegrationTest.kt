package pl.kacosmetology.scheduler.reservation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Request identity retains field boundaries, null types and every option before recognizing a successful retry. */
class ReservationCommandFingerprintIntegrationTest : ReservationContractTestSupport() {
    @Test
    fun `moving comma separated text between private and customer explanations conflicts`() {
        val id = reservation()
        val first = mapOf("reason" to "a, b", "customerExplanation" to "c")
        assertEquals(204, action(id, "cancel-by-staff", 0, first).response.status)
        val audits = count("reservation_audit")
        val changed = action(id, "cancel-by-staff", 0, mapOf("reason" to "a", "customerExplanation" to "b, c"))
        assertEquals(409, changed.response.status)
        assertEquals("INVALID_STATUS_TRANSITION", tree(changed)["code"].asString())
        assertEquals(204, action(id, "cancel-by-staff", 0, first).response.status)
        assertEquals(audits, count("reservation_audit"))
        val row =
            jdbc.queryForMap(
                "SELECT internal_cancellation_reason,customer_explanation FROM reservations WHERE id=?",
                id,
            )
        assertEquals("a, b", row["internal_cancellation_reason"])
        assertEquals("c", row["customer_explanation"])
    }

    @Test
    fun `null and literal null remain distinct in both explanation fields`() {
        val id = reservation(localNow.minusMinutes(5))
        assertEquals(204, action(id, "complete", 0).response.status)
        val audits = count("reservation_audit")
        for (field in listOf("reason", "customerExplanation")) {
            assertEquals(409, action(id, "complete", 0, mapOf(field to "null")).response.status)
        }
        assertEquals(204, action(id, "complete", 0).response.status)
        assertEquals(audits, count("reservation_audit"))
    }

    @Test
    fun `exact Unicode and escaped text replays but changes to either option conflict`() {
        val id = reservation()
        val body =
            mapOf(
                "reason" to "Zmiana, \"żółć\"\nNowy wiersz",
                "customerExplanation" to "Kontakt\\salon, dzień",
                "notifyCustomer" to false,
                "overrideEmployeeBlock" to false,
            )
        assertEquals(204, action(id, "cancel-by-staff", 0, body).response.status)
        val audits = count("reservation_audit")
        for (field in listOf("notifyCustomer", "overrideEmployeeBlock")) {
            assertEquals(409, action(id, "cancel-by-staff", 0, body + (field to true)).response.status)
        }
        assertEquals(204, action(id, "cancel-by-staff", 0, body).response.status)
        assertEquals(audits, count("reservation_audit"))
        assertEquals(0L, count("notification_deliveries"))
    }

    @Test
    fun `legacy ambiguous fingerprint cannot acknowledge a retry or mutate the saved outcome`() {
        val id = reservation(localNow.minusMinutes(5))
        assertEquals(204, action(id, "complete", 0).response.status)
        // Pre-fix SHA-256 of the ambiguous text "[null, null, false, false]".
        val legacyHash = "aae95a7b1a85c1725e8b1a1fb750c8e730944e680c126b96fc699e927b7c3a05"
        jdbc.update("UPDATE reservation_commands SET request_hash=? WHERE reservation_id=?", legacyHash, id)
        val commands = jdbc.queryForList("SELECT * FROM reservation_commands WHERE reservation_id=?", id)
        val audits = count("reservation_audit")
        for (body in listOf(emptyMap<String, String>(), mapOf("reason" to "null"))) {
            val result = action(id, "complete", 0, body)
            assertEquals(409, result.response.status)
            assertEquals("INVALID_STATUS_TRANSITION", tree(result)["code"].asString())
        }
        assertEquals(commands, jdbc.queryForList("SELECT * FROM reservation_commands WHERE reservation_id=?", id))
        assertEquals("COMPLETED", state(id))
        assertEquals(1L, version(id))
        assertEquals(audits, count("reservation_audit"))
    }
}
