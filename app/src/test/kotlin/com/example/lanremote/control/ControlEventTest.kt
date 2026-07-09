package com.example.lanremote.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlEventTest {
    @Test fun parsesTap() {
        val e = ControlEventParser.parse("""{"type":"tap","x":0.5,"y":0.25}""")
        assertEquals(ControlEvent.Tap(0.5f, 0.25f), e)
    }

    @Test fun parsesLongPress() {
        val e = ControlEventParser.parse("""{"type":"longpress","x":0.1,"y":0.2,"durationMs":600}""")
        assertEquals(ControlEvent.LongPress(0.1f, 0.2f, 600L), e)
    }

    @Test fun parsesSwipe() {
        val e = ControlEventParser.parse(
            """{"type":"swipe","x1":0.1,"y1":0.2,"x2":0.3,"y2":0.4,"durationMs":150}"""
        )
        assertEquals(ControlEvent.Swipe(0.1f, 0.2f, 0.3f, 0.4f, 150L), e)
    }

    @Test fun parsesKey() {
        val e = ControlEventParser.parse("""{"type":"key","action":"back"}""")
        assertEquals(ControlEvent.Key(KeyAction.BACK), e)
    }

    @Test fun parsesText() {
        val e = ControlEventParser.parse("""{"type":"text","value":"hi"}""")
        assertEquals(ControlEvent.Text("hi"), e)
    }

    @Test fun rejectsOutOfRange() {
        assertNull(ControlEventParser.parse("""{"type":"tap","x":1.5,"y":0.1}"""))
    }

    @Test fun rejectsGarbage() {
        assertNull(ControlEventParser.parse("""not json"""))
        assertNull(ControlEventParser.parse("""{"type":"nope"}"""))
    }
}
