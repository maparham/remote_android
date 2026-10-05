package com.example.lanremote.video

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSettingsTest {
    @Test fun defaultsMatchPreviousBehaviour() {
        assertEquals(StreamSettings(scalePercent = 100, fps = 30, jpegQuality = 55), StreamSettings.DEFAULT)
    }

    @Test fun sanitizeReplacesUnknownValuesWithDefaults() {
        assertEquals(StreamSettings.DEFAULT, StreamSettings(37, 7, 99).sanitized())
        assertEquals(StreamSettings(50, 60, 75), StreamSettings(50, 60, 75).sanitized())
    }

    @Test fun mergeOnlyOverridesProvidedFields() {
        val base = StreamSettings(75, 30, 55)
        assertEquals(StreamSettings(50, 30, 55), base.merge(scalePercent = 50, fps = null, jpegQuality = null))
        assertEquals(StreamSettings(75, 15, 40), base.merge(scalePercent = null, fps = 15, jpegQuality = 40))
    }

    @Test fun mergeIgnoresInvalidValues() {
        val base = StreamSettings(75, 30, 55)
        assertEquals(base, base.merge(scalePercent = 33, fps = 1000, jpegQuality = -1))
    }

    @Test fun jsonCarriesTypeAndValues() {
        assertEquals(
            """{"type":"settings","scale":50,"fps":15,"jpegQuality":40}""",
            StreamSettings(50, 15, 40).toJson()
        )
    }
}
