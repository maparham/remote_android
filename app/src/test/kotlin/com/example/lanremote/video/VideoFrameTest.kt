package com.example.lanremote.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameTest {
    @Test fun roundTripsKeyframe() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = VideoFrame.encode(true, payload)
        assertTrue(VideoFrame.decodeHeader(frame))
        assertArrayEquals(payload, VideoFrame.payload(frame))
    }

    @Test fun roundTripsDeltaFrame() {
        val payload = byteArrayOf(9, 8, 7)
        val frame = VideoFrame.encode(false, payload)
        assertFalse(VideoFrame.decodeHeader(frame))
        assertArrayEquals(payload, VideoFrame.payload(frame))
    }

    @Test fun metaJson() {
        assertEquals("""{"width":1080,"height":2400}""", VideoMeta(1080, 2400).toJson())
    }
}
