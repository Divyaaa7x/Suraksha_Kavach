package org.kavach.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetDetentTest {
    private val anchors = floatArrayOf(900f, 500f, 50f) // PEEK, HALF, FULL

    @Test
    fun slowReleaseSnapsToNearest() {
        assertEquals(Detent.HALF, restingDetent(560f, 0f, anchors))
        assertEquals(Detent.PEEK, restingDetent(800f, 0f, anchors))
    }

    @Test
    fun flingCarriesPastTheNearestStop() {
        assertEquals(Detent.FULL, restingDetent(480f, -3000f, anchors))
        assertEquals(Detent.PEEK, restingDetent(520f, 3000f, anchors))
    }
}
