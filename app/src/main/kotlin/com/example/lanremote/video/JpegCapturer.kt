package com.example.lanremote.video

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * MJPEG capture: the screen is mirrored into an ImageReader and each frame is compressed to
 * JPEG. Works in any browser over plain HTTP (no WebCodecs / secure context required).
 */
class JpegCapturer(
    private val width: Int,
    private val height: Int,
    private val quality: Int,
    fps: Int
) : CapturePipeline {
    private val minFrameIntervalMs: Long = 1000L / fps.coerceAtLeast(1)
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastEmit = 0L

    override val surface: Surface
        get() = reader?.surface ?: error("JpegCapturer not started")

    fun start(onFrame: (ByteArray) -> Unit) {
        val ht = HandlerThread("mjpeg").also { it.start() }
        thread = ht
        val handler = Handler(ht.looper)
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
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
        try { reader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        reader = null
        thread = null
    }
}
