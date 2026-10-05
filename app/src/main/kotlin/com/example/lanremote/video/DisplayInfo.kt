package com.example.lanremote.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** Real size of the default display in its current rotation. Safe from non-visual contexts. */
object DisplayInfo {
    data class Size(val width: Int, val height: Int, val densityDpi: Int)

    fun real(ctx: Context): Size {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return Size(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }
}
