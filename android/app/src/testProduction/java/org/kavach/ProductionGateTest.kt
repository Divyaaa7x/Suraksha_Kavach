package org.kavach

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kavach.core.Signed

class ProductionGateTest {
    @Test
    fun reportSigningFailsUntilResponderEnrollmentIsImplemented() {
        val error = assertThrows(IllegalStateException::class.java) {
            Signed.observation("asset", "road_blocked", 18.0, 78.0, 10.0, "", "pack")
        }
        assertTrue(error.message!!.contains("Production responder enrollment"))
    }
}
