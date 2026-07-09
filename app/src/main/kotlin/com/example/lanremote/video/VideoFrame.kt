package com.example.lanremote.video

data class VideoMeta(val width: Int, val height: Int) {
    fun toJson(): String = """{"width":$width,"height":$height}"""
}

object VideoFrame {
    private const val FLAG_KEYFRAME: Int = 0x01

    fun encode(isKeyframe: Boolean, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = (if (isKeyframe) FLAG_KEYFRAME else 0).toByte()
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }

    fun decodeHeader(frame: ByteArray): Boolean = (frame[0].toInt() and FLAG_KEYFRAME) != 0

    fun payload(frame: ByteArray): ByteArray = frame.copyOfRange(1, frame.size)
}
