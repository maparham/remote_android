package com.example.lanremote.video

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder

/**
 * Captures device *playback* audio (media/game) via MediaProjection and encodes it to Opus.
 * Independent of the video pipeline (uses AudioRecord, not a VirtualDisplay), so it can run
 * alongside either H.264 or MJPEG capture. Requires the RECORD_AUDIO permission.
 *
 * Only media/game/unknown-usage audio from apps that permit playback capture is available;
 * voice calls and apps that opt out (allowAudioPlaybackCapture=false) are silent by OS policy.
 */
class AudioCapturer(private val projection: MediaProjection) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private val sampleRate = 48_000
    private val channels = 2
    private val frameSize = 960 // samples/channel = 20 ms @ 48 kHz

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before constructing
    fun start(onPacket: (ByteArray) -> Unit) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, frameSize * channels * 2 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = rec
        rec.startRecording()
        running = true

        val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO)
        encoder.setBitrate(64_000)
        encoder.setComplexity(5)
        encoder.setUseVBR(true)

        thread = Thread {
            val pcm = ShortArray(frameSize * channels) // interleaved L,R
            val out = ByteArray(4000)
            while (running) {
                var read = 0
                while (read < pcm.size && running) {
                    val r = rec.read(pcm, read, pcm.size - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < pcm.size) continue
                try {
                    val len = encoder.encode(pcm, 0, frameSize, out, 0, out.size)
                    if (len > 0) onPacket(out.copyOf(len))
                } catch (_: Exception) {
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        record = null
        thread = null
    }
}
