package com.example.lanremote.video

/** Minimal Annex-B helpers for the H.264 byte stream MediaCodec emits. */
object AnnexB {
    /** NAL unit type of the first NAL after a 3- or 4-byte start code, or -1 if none. */
    fun firstNalType(data: ByteArray): Int {
        val headerIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 1.toByte() -> 3
            else -> return -1
        }
        return data[headerIndex].toInt() and 0x1F
    }

    const val NAL_SPS = 7
}

/**
 * Makes every keyframe self-contained. MediaCodec emits SPS/PPS once as a separate
 * codec-config buffer; sending that to the browser as a "key" chunk makes WebCodecs reject it
 * (it holds no picture). Instead the config is cached and prepended to any keyframe that does
 * not already start with an SPS.
 */
class KeyframeAssembler {
    private var config: ByteArray? = null

    /** Returns (isKey, bytes) to send, or null when the buffer was only codec config. */
    fun process(isConfig: Boolean, isKey: Boolean, data: ByteArray): Pair<Boolean, ByteArray>? {
        if (isConfig) {
            config = data
            return null
        }
        if (!isKey) return Pair(false, data)
        val c = config
        return if (c != null && AnnexB.firstNalType(data) != AnnexB.NAL_SPS) Pair(true, c + data)
        else Pair(true, data)
    }
}
