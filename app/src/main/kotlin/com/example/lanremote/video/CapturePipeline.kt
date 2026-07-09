package com.example.lanremote.video

/** A running screen-capture pipeline (H.264 or MJPEG). Stopped when the client disconnects. */
interface CapturePipeline {
    fun stop()
}
