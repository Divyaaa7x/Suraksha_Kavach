package org.kavach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kavach.pack.PackException
import org.kavach.pack.PackLoader
import java.io.File
import java.security.MessageDigest

/** Loads the REAL OSM pack that ships in the APK, and checks tamper detection. */
class PackLoaderTest {

    private val packDir = File("src/main/assets/pack")

    @Test
    fun realPackVerifiesAndIsOsmSourced() {
        assumeTrue("the bundled fixture is signed with the demo key", BuildConfig.DEMO_MODE)
        val pack = PackLoader.load({ File(packDir, it).readBytes() })
        assertEquals("OSM", pack.sourceClass)
        assertTrue(pack.graph.crossings.isNotEmpty())
        assertTrue(pack.features.isNotEmpty())
        assertEquals(36, pack.indiaAreas.size)
        assertTrue(pack.indiaAreas.any { it.code == "IN-TG" })
        assertTrue(pack.attribution.contains("OpenStreetMap contributors"))
        assertTrue(pack.graph.precomputedCriticality != null)
        val map = requireNotNull(pack.offlineMap)
        assertTrue(map.databaseBytes > 100_000_000L)
        val archive = File("src/main/assets/maps/telangana-map.mapdata")
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        assertEquals(map.archiveSha256, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    @Test
    fun tamperedGraphIsRejected() {
        assumeTrue("the bundled fixture is signed with the demo key", BuildConfig.DEMO_MODE)
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
        assumeTrue("the bundled fixture is signed with the demo key", BuildConfig.DEMO_MODE)
        try {
            PackLoader.load({ File(packDir, it).readBytes() }, minCreatedAt = "2099-01-01T00:00:00+00:00")
            fail("downgrade must be rejected")
        } catch (e: PackException) {
            assertTrue(e.message!!.contains("downgrade"))
        }
    }
}
