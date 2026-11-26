package org.kavach.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxBlurTest {
    @Test
    fun flatStaysFlatAndSpikeSpreads() {
        val flat = IntArray(9) { 0xFF102030.toInt() }
        assertEquals(flat.toList(), boxBlur(flat, 3, 3, 1, horizontal = true).toList())
        val spike = intArrayOf(0, 0, 0x5A, 0, 0)
        val out = boxBlur(spike, 5, 1, 1, horizontal = true).map { it and 255 }
        assertEquals(listOf(0, 30, 30, 30, 0), out)
    }
}
