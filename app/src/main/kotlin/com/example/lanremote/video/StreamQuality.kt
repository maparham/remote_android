package com.example.lanremote.video

/**
 * Pure helpers that turn [StreamSettings] into encoder parameters. The capture pipelines are
 * created at the scaled size, and the H.264 bitrate follows pixel count and frame rate so a
 * smaller or slower stream is actually cheaper.
 */
object StreamQuality {
    private const val REFERENCE_PIXELS = 1080L * 2400L
    private const val REFERENCE_FPS = 30L
    private const val REFERENCE_BITRATE = 6_000_000L
    private const val MIN_BITRATE = 1_000_000L
    private const val MAX_BITRATE = 12_000_000L

    /** Scales a display size; both sides are rounded down to even (H.264 needs even dims). */
    fun scaledSize(width: Int, height: Int, percent: Int): Pair<Int, Int> {
        val p = sanitizePercent(percent)
        return Pair(toEven(width * p / 100), toEven(height * p / 100))
    }

    /** 6 Mbps at 1080×2400 and 30 fps, proportional to pixels × fps, clamped to [1, 12] Mbps. */
    fun bitrateFor(width: Int, height: Int, fps: Int): Int {
        val bps = REFERENCE_BITRATE * (width.toLong() * height) / REFERENCE_PIXELS * fps / REFERENCE_FPS
        return bps.coerceIn(MIN_BITRATE, MAX_BITRATE).toInt()
    }

    fun sanitizePercent(percent: Int): Int =
        if (percent in StreamSettings.SCALE_OPTIONS) percent else StreamSettings.DEFAULT.scalePercent

    private fun toEven(v: Int): Int = maxOf(2, v - (v % 2))
}
