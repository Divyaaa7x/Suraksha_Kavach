package org.kavach.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class IndiaOnlineTest {
    @Test
    fun parsesPhotonResultsAndSkipsUnnamed() {
        val json = """{"features":[
            {"properties":{"name":"Charminar","city":"Hyderabad","state":"Telangana","country":"India"},
             "geometry":{"coordinates":[78.47,17.36]}},
            {"properties":{},"geometry":{"coordinates":[1.0,2.0]}}]}"""
        val places = parsePhoton(json)
        assertEquals(1, places.size)
        assertEquals("Hyderabad, Telangana, India", places[0].detail)
        assertEquals(17.36, places[0].lat, 1e-9)
    }

    @Test
    fun routeGeoJsonUsesLonLatOrderAndSkipsSinglePoints() {
        val out = linesGeoJson(listOf(listOf(17.0 to 78.0, 18.0 to 79.0), listOf(1.0 to 2.0)))
        assertEquals(true, out.contains("[[78.0,17.0],[79.0,18.0]]"))
        assertEquals(false, out.contains("[2.0,1.0]"))
    }
}
