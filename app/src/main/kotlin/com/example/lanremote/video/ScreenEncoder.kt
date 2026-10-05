package com.example.lanremote.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface

class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val bitrate: Int = 6_000_000
) : CapturePipeline {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(
        projection: MediaProjection,
        onMeta: (VideoMeta) -> Unit,
        onFrame: (Boolean, ByteArray) -> Unit
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Pin baseline profile so the client's avc1.42E01E codec string is honest.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            // Every IDR carries SPS/PPS so a late-joining client can decode. (API 29+)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = c.createInputSurface()
        c.start()
        codec = c
        display = projection.createVirtualDisplay(
            "lanremote", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        onMeta(VideoMeta(width, height))
        running = true
        thread = Thread { drain(onFrame) }.also { it.start() }
    }

    private fun drain(onFrame: (Boolean, ByteArray) -> Unit) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try {
                c.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (idx >= 0) {
                val buf = c.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buf.get(data)
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    onFrame(isKey, data)
                }
                c.releaseOutputBuffer(idx, false)
            }
        }
    }

    /** Force an immediate IDR (which, with prepend-header set, carries SPS/PPS). */
    fun requestKeyframe() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        surface?.release()
        codec = null
        surface = null
        display = null
    }
}
