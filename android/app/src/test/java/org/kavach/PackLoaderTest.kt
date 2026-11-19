package org.kavach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kavach.pack.PackException
import org.kavach.pack.PackLoader
import java.io.File

/** Loads the REAL OSM pack that ships in the APK, and checks tamper detection. */
class PackLoaderTest {

    private val packDir = File("src/main/assets/pack")

    @Test
    fun realPackVerifiesAndIsOsmSourced() {
        val pack = PackLoader.load({ File(packDir, it).readBytes() })
        assertEquals("OSM", pack.sourceClass)
        assertTrue(pack.graph.crossings.isNotEmpty())
        assertTrue(pack.features.isNotEmpty())
        assertTrue(pack.attribution.contains("OpenStreetMap contributors"))
        assertTrue(pack.graph.precomputedCriticality != null)
    }

    @Test
    fun tamperedGraphIsRejected() {
        val bytes = File(packDir, "graph.json").readBytes()
        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        try {
            PackLoader.load({ name -> if (name == "graph.json") tampered else File(packDir, name).readBytes() })
            fail("tampered pack must be rejected")
        } catch (e: PackException) {
            assertTrue(e.message!!.contains("hash mismatch"))
        }
    }

    @Test
    fun downgradeIsRejected() {
        try {
            PackLoader.load({ File(packDir, it).readBytes() }, minCreatedAt = "2099-01-01T00:00:00+00:00")
            fail("downgrade must be rejected")
        } catch (e: PackException) {
            assertTrue(e.message!!.contains("downgrade"))
        }
    }
}
