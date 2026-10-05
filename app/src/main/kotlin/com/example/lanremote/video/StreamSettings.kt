package com.example.lanremote.video

/**
 * User-selectable stream settings. Every field is restricted to a small set of options so the
 * phone UI, the browser and the encoder all agree on what a value means.
 */
data class StreamSettings(
    val scalePercent: Int,
    val fps: Int,
    val jpegQuality: Int
) {
    fun sanitized(): StreamSettings = StreamSettings(
        scalePercent = scalePercent.takeIf { it in SCALE_OPTIONS } ?: DEFAULT.scalePercent,
        fps = fps.takeIf { it in FPS_OPTIONS } ?: DEFAULT.fps,
        jpegQuality = jpegQuality.takeIf { it in JPEG_QUALITY_OPTIONS } ?: DEFAULT.jpegQuality
    )

    /** Applies the provided fields; null or invalid values keep the current value. */
    fun merge(scalePercent: Int?, fps: Int?, jpegQuality: Int?): StreamSettings = StreamSettings(
        scalePercent = scalePercent?.takeIf { it in SCALE_OPTIONS } ?: this.scalePercent,
        fps = fps?.takeIf { it in FPS_OPTIONS } ?: this.fps,
        jpegQuality = jpegQuality?.takeIf { it in JPEG_QUALITY_OPTIONS } ?: this.jpegQuality
    )

    fun toJson(): String =
        """{"type":"settings","scale":$scalePercent,"fps":$fps,"jpegQuality":$jpegQuality}"""

    companion object {
        val SCALE_OPTIONS = listOf(100, 75, 50)
        val FPS_OPTIONS = listOf(15, 30, 60)
        /** Low / Medium / High. Medium keeps the previous hardcoded value. */
        val JPEG_QUALITY_OPTIONS = listOf(40, 55, 75)
        val DEFAULT = StreamSettings(scalePercent = 100, fps = 30, jpegQuality = 55)
    }
}
