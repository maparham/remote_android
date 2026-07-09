package com.example.lanremote.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Foreground service that owns the MediaProjection. Capture pipelines (H.264 or MJPEG) are
 * started on demand when a browser connects to /video or /mjpeg, and stopped on disconnect.
 * Only one pipeline runs at a time (one VirtualDisplay per projection).
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var pipeline: CapturePipeline? = null
    private var audioPipeline: AudioCapturer? = null
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent!!.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)
        projection = proj

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        meta = VideoMeta(width, height)
        controller = ServiceController()
        com.example.lanremote.Stats.onShareStart(width, height)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopPipeline()
        stopAudio()
        projection?.stop(); projection = null
        meta = null
        controller = null
        com.example.lanremote.Stats.reset()
        super.onDestroy()
    }

    @Synchronized
    private fun stopPipeline() {
        pipeline?.stop()
        pipeline = null
    }

    @Synchronized
    private fun startAudio(onPacket: (ByteArray) -> Unit): Boolean {
        val proj = projection ?: return false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        stopAudio()
        return try {
            val cap = AudioCapturer(proj)
            cap.start(onPacket)
            audioPipeline = cap
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    private fun stopAudio() {
        audioPipeline?.stop()
        audioPipeline = null
    }

    @Synchronized
    private fun startH264(onFrame: (Boolean, ByteArray) -> Unit): (() -> Unit)? {
        val proj = projection ?: return null
        stopPipeline()
        val enc = ScreenEncoder(width, height, densityDpi)
        enc.start(proj, { }, onFrame)
        pipeline = enc
        return { enc.requestKeyframe() }
    }

    @Synchronized
    private fun startMjpeg(onFrame: (ByteArray) -> Unit) {
        val proj = projection ?: return
        stopPipeline()
        val cap = JpegCapturer(width, height, densityDpi)
        cap.start(proj, { }, onFrame)
        pipeline = cap
    }

    /** Bridges the server (no Service reference) to this instance's pipeline controls. */
    inner class ServiceController {
        fun startH264(onFrame: (Boolean, ByteArray) -> Unit): (() -> Unit)? =
            this@CaptureService.startH264(onFrame)
        fun startMjpeg(onFrame: (ByteArray) -> Unit) = this@CaptureService.startMjpeg(onFrame)
        fun stopPipeline() = this@CaptureService.stopPipeline()
        fun startAudio(onPacket: (ByteArray) -> Unit): Boolean = this@CaptureService.startAudio(onPacket)
        fun stopAudio() = this@CaptureService.stopAudio()
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("LAN Remote")
            .setContentText("Screen is being shared on your LAN")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1

        /** Device screen resolution, set once the projection is live. */
        @Volatile var meta: VideoMeta? = null

        /** Pipeline control surface for the server; null when not sharing. */
        @Volatile var controller: ServiceController? = null
    }
}
