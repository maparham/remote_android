package com.example.lanremote.video

import android.view.Surface

/**
 * A running screen-capture pipeline (H.264 or MJPEG). It only owns its input [surface];
 * [CaptureService] owns the single VirtualDisplay and points it at that surface. Android 14+
 * allows one createVirtualDisplay call per MediaProjection, so pipelines must never make one.
 */
interface CapturePipeline {
    val surface: Surface
    fun stop()
}
