package com.example.lanremote.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface

/** H.264 encoder fed by an input surface. Emits self-contained keyframes (SPS/PPS + IDR). */
class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int
) : CapturePipeline {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    override val surface: Surface
        get() = inputSurface ?: error("ScreenEncoder not started")

    fun start(onFrame: (Boolean, ByteArray) -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // Caps how many surface frames reach the encoder; KEY_FRAME_RATE alone is only a hint.
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Pin baseline profile so the client's avc1.42E01E codec string is honest.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            // Ask for SPS/PPS on every IDR; KeyframeAssembler covers encoders that ignore this.
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
        running = true
        thread = Thread { drain(onFrame) }.also { it.start() }
    }

    private fun drain(onFrame: (Boolean, ByteArray) -> Unit) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        val assembler = KeyframeAssembler()
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
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    assembler.process(isConfig, isKey, data)?.let { (key, bytes) -> onFrame(key, bytes) }
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
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        inputSurface?.release()
        codec = null
        inputSurface = null
    }
}
