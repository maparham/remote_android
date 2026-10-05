package com.example.lanremote.video

import android.content.Context

/** Persists the user's stream-resolution choice. Read by [CaptureService] when sharing starts. */
object QualityPrefs {
    private const val FILE = "quality"
    private const val KEY_SCALE = "scale_percent"

    fun scalePercent(ctx: Context): Int =
        StreamQuality.sanitizePercent(
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(KEY_SCALE, StreamQuality.DEFAULT_PERCENT)
        )

    fun setScalePercent(ctx: Context, percent: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SCALE, StreamQuality.sanitizePercent(percent))
            .apply()
    }
}
