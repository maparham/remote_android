package com.example.lanremote.video

/**
 * Pure helpers for the user-selectable stream resolution. The user picks a percentage of the
 * native display size; the capture pipelines are created at the scaled size and the H.264
 * bitrate is scaled with the pixel count so a smaller stream is actually cheaper.
 */
object StreamQuality {
    val PERCENT_OPTIONS = listOf(100, 75, 50)
    const val DEFAULT_PERCENT = 100

    private const val REFERENCE_PIXELS = 1080L * 2400L
    private const val REFERENCE_BITRATE = 6_000_000L
    private const val MIN_BITRATE = 1_000_000
    private const val MAX_BITRATE = 12_000_000

    /** Scales a display size; both sides are rounded down to even (H.264 needs even dims). */
    fun scaledSize(width: Int, height: Int, percent: Int): Pair<Int, Int> {
        val p = sanitizePercent(percent)
        return Pair(toEven(width * p / 100), toEven(height * p / 100))
    }

    /** Bitrate proportional to pixel count, 6 Mbps at 1080×2400, clamped to [1, 12] Mbps. */
    fun bitrateFor(width: Int, height: Int): Int {
        val bps = REFERENCE_BITRATE * (width.toLong() * height) / REFERENCE_PIXELS
        return bps.coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }

    fun sanitizePercent(percent: Int): Int =
        if (percent in PERCENT_OPTIONS) percent else DEFAULT_PERCENT

    private fun toEven(v: Int): Int = maxOf(2, v - (v % 2))
}
