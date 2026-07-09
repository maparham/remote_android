package com.example.lanremote.video

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.io.ByteArrayOutputStream

/**
 * MJPEG capture: mirrors the screen into an ImageReader, compresses each frame to JPEG.
 * Works in any browser over plain HTTP (no WebCodecs / secure context required).
 */
class JpegCapturer(
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val quality: Int = 55,
    private val minFrameIntervalMs: Long = 66 // ~15 fps cap
) : CapturePipeline {

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var lastEmit = 0L

    fun start(
        projection: MediaProjection,
        onMeta: (VideoMeta) -> Unit,
        onFrame: (ByteArray) -> Unit
    ) {
        val ht = HandlerThread("mjpeg").also { it.start() }
        thread = ht
        val handler = Handler(ht.looper)
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        display = projection.createVirtualDisplay(
            "lanremote-mjpeg", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        onMeta(VideoMeta(width, height))
        r.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.nanoTime() / 1_000_000
                if (now - lastEmit < minFrameIntervalMs) return@setOnImageAvailableListener
                lastEmit = now

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + rowPadding / pixelStride
                val bmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                val cropped = if (paddedWidth != width) Bitmap.createBitmap(bmp, 0, 0, width, height) else bmp
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (cropped !== bmp) cropped.recycle()
                bmp.recycle()
                onFrame(out.toByteArray())
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, handler)
    }

    override fun stop() {
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        display = null
        reader = null
        thread = null
    }
}
