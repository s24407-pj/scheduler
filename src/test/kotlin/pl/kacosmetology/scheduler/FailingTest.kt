package pl.kacosmetology.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FailingTest {

    @Test
    fun `this test should fail on CI`() {
        assertEquals(1, 2, "Intentional failure to verify CI catches failing tests")
    }
}

