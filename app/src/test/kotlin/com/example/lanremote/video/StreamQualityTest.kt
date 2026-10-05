package com.example.lanremote.video

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamQualityTest {
    @Test fun fullScaleKeepsNativeSize() {
        assertEquals(Pair(1080, 2400), StreamQuality.scaledSize(1080, 2400, 100))
    }

    @Test fun halfScaleHalvesBothDimensions() {
        assertEquals(Pair(540, 1200), StreamQuality.scaledSize(1080, 2400, 50))
    }

    @Test fun scaledDimensionsAreRoundedDownToEven() {
        // 1080 * 0.75 = 810 (even), 2400 * 0.75 = 1800 (even)
        assertEquals(Pair(810, 1800), StreamQuality.scaledSize(1080, 2400, 75))
        // 1170 * 0.75 = 877.5 -> 876 ; 2532 * 0.75 = 1899 -> 1898
        assertEquals(Pair(876, 1898), StreamQuality.scaledSize(1170, 2532, 75))
    }

    @Test fun neverCollapsesBelowMinimumSize() {
        assertEquals(Pair(2, 2), StreamQuality.scaledSize(1, 1, 50))
    }

    @Test fun bitrateScalesWithPixelCountFromSixMbpsAtFullHdThirtyFps() {
        assertEquals(6_000_000, StreamQuality.bitrateFor(1080, 2400, 30))
        assertEquals(1_500_000, StreamQuality.bitrateFor(540, 1200, 30))
    }

    @Test fun bitrateScalesWithFrameRate() {
        assertEquals(3_000_000, StreamQuality.bitrateFor(1080, 2400, 15))
        assertEquals(12_000_000, StreamQuality.bitrateFor(1080, 2400, 60))
    }

    @Test fun bitrateIsClampedToSaneBounds() {
        assertEquals(1_000_000, StreamQuality.bitrateFor(100, 100, 30))
        assertEquals(12_000_000, StreamQuality.bitrateFor(4000, 8000, 30))
    }

    @Test fun unknownPercentFallsBackToFull() {
        assertEquals(100, StreamQuality.sanitizePercent(37))
        assertEquals(50, StreamQuality.sanitizePercent(50))
    }
}
