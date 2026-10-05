package com.example.lanremote.video

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists [StreamSettings]. This is the single source of truth: the phone UI, the browser
 * (via the control socket) and the capture service all read and write here, and react to
 * changes through [listen].
 */
object QualityPrefs {
    private const val FILE = "quality"
    private const val KEY_SCALE = "scale_percent"
    private const val KEY_FPS = "fps"
    private const val KEY_JPEG = "jpeg_quality"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): StreamSettings {
        val p = prefs(ctx)
        val d = StreamSettings.DEFAULT
        return StreamSettings(
            p.getInt(KEY_SCALE, d.scalePercent),
            p.getInt(KEY_FPS, d.fps),
            p.getInt(KEY_JPEG, d.jpegQuality)
        ).sanitized()
    }

    fun save(ctx: Context, settings: StreamSettings) {
        val s = settings.sanitized()
        prefs(ctx).edit()
            .putInt(KEY_SCALE, s.scalePercent)
            .putInt(KEY_FPS, s.fps)
            .putInt(KEY_JPEG, s.jpegQuality)
            .apply()
    }

    /**
     * Calls [onChange] on the main thread whenever a setting changes. SharedPreferences holds
     * listeners weakly, so the caller must keep the returned object and pass it to [unlisten].
     */
    fun listen(ctx: Context, onChange: (StreamSettings) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val appCtx = ctx.applicationContext
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange(load(appCtx)) }
        prefs(appCtx).registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun unlisten(ctx: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(ctx.applicationContext).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
