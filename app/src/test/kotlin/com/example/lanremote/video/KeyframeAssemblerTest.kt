package com.example.lanremote.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeAssemblerTest {
    // NAL headers: 0x67 = SPS (type 7), 0x68 = PPS (type 8), 0x65 = IDR slice (type 5), 0x41 = non-IDR slice (type 1)
    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 3)
    private val config = sps + pps
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 9, 9)
    private val delta = byteArrayOf(0, 0, 1, 0x41, 7)

    @Test fun firstNalTypeHandlesThreeAndFourByteStartCodes() {
        assertEquals(7, AnnexB.firstNalType(sps))
        assertEquals(1, AnnexB.firstNalType(delta))
        assertEquals(-1, AnnexB.firstNalType(byteArrayOf(1, 2, 3)))
    }

    @Test fun codecConfigIsHeldBackNotEmittedAsAKeyframe() {
        val a = KeyframeAssembler()
        assertNull(a.process(isConfig = true, isKey = false, data = config))
    }

    @Test fun keyframeWithoutSpsGetsCachedConfigPrepended() {
        val a = KeyframeAssembler()
        a.process(isConfig = true, isKey = false, data = config)
        val out = a.process(isConfig = false, isKey = true, data = idr)!!
        assertTrue(out.first)
        assertArrayEquals(config + idr, out.second)
    }

    @Test fun keyframeThatAlreadyStartsWithSpsIsUntouched() {
        val a = KeyframeAssembler()
        a.process(isConfig = true, isKey = false, data = config)
        val out = a.process(isConfig = false, isKey = true, data = config + idr)!!
        assertArrayEquals(config + idr, out.second)
    }

    @Test fun deltaFramesPassThrough() {
        val a = KeyframeAssembler()
        val out = a.process(isConfig = false, isKey = false, data = delta)!!
        assertFalse(out.first)
        assertArrayEquals(delta, out.second)
    }
}
