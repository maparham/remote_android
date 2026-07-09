package com.example.lanremote.control

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    @Test fun mapsCenter() {
        val m = CoordinateMapper(1080, 2400)
        assertEquals(Pair(539, 1199), m.toPixels(0.5f, 0.5f))
    }

    @Test fun clampsOutOfRange() {
        val m = CoordinateMapper(1080, 2400)
        assertEquals(Pair(1079, 2399), m.toPixels(2.0f, 2.0f))
        assertEquals(Pair(0, 0), m.toPixels(-1.0f, -1.0f))
    }
}
