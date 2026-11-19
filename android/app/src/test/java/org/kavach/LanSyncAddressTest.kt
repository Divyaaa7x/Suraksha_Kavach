package org.kavach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kavach.sync.isPrivateHubUrl
import java.net.URL

class LanSyncAddressTest {
    @Test
    fun acceptsOnlyPrivateLanHostsAndLocalLoopback() {
        listOf("10.20.30.40", "192.168.50.2", "172.16.0.1", "172.31.255.254",
            "127.0.0.1", "localhost", "10.0.2.2").forEach {
            assertTrue("$it should be accepted", isPrivateHubUrl(URL("http://$it:8000")))
        }
        listOf("8.8.8.8", "172.32.0.1", "192.168.attacker", "10.999.1.2").forEach {
            assertFalse("$it should be rejected", isPrivateHubUrl(URL("http://$it")))
        }
        assertFalse(isPrivateHubUrl(URL("http://user@10.1.2.3")))
        assertFalse(isPrivateHubUrl(URL("http://10.1.2.3/prefix")))
        assertFalse(isPrivateHubUrl(URL("ftp://10.1.2.3")))
        assertFalse(isPrivateHubUrl(URL("https://coordinator.example")))
    }
}
