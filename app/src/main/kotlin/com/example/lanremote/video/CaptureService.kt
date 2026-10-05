package com.example.lanremote.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import com.example.lanremote.Stats
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Foreground service that owns the MediaProjection and its single VirtualDisplay.
 *
 * Capture pipelines (H.264 or MJPEG) are started when a browser connects to /video or /mjpeg
 * and stopped on disconnect. Only one runs at a time. Android 14+ allows a single
 * createVirtualDisplay call per projection, so the display is created once and then resized
 * and re-pointed at each new pipeline's surface.
 *
 * When the stream settings or the screen rotation change, the active pipeline is rebuilt at
 * the new size and connected viewers are told the new dimensions; sharing never stops.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var pipeline: CapturePipeline? = null
    private var audioPipeline: AudioCapturer? = null

    private var settings = StreamSettings.DEFAULT
    private var width = 0
    private var height = 0
    private var densityDpi = 0

    /** Frame sink of the active viewer; kept so the pipeline can be rebuilt on reconfigure. */
    private var h264Sink: ((Boolean, ByteArray) -> Unit)? = null
    private var mjpegSink: ((ByteArray) -> Unit)? = null
    private val metaListeners = CopyOnWriteArraySet<(VideoMeta) -> Unit>()

    private val worker = HandlerThread("capture-ctl").also { it.start() }
    private val workerHandler = Handler(worker.looper)
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) reconfigure()
        }
    }

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

        settings = QualityPrefs.load(this)
        updateSize()
        controller = ServiceController()
        Stats.onShareStart(width, height)

        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, workerHandler)
        // Prefs callbacks arrive on the main thread; rebuilding a pipeline can block briefly.
        prefsListener = QualityPrefs.listen(this) { workerHandler.post { reconfigure() } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        prefsListener?.let { QualityPrefs.unlisten(this, it) }
        prefsListener = null
        synchronized(this) {
            h264Sink = null
            mjpegSink = null
            detachPipeline()
            display?.release(); display = null
        }
        stopAudio()
        projection?.stop(); projection = null
        meta = null
        controller = null
        metaListeners.clear()
        Stats.reset()
        worker.quitSafely()
        super.onDestroy()
    }

    /** Recomputes the stream size from the current display and settings. True if it changed. */
    private fun updateSize(): Boolean {
        val real = DisplayInfo.real(this)
        val (w, h) = StreamQuality.scaledSize(real.width, real.height, settings.scalePercent)
        densityDpi = real.densityDpi
        if (w == width && h == height) return false
        width = w
        height = h
        meta = VideoMeta(w, h)
        Stats.width = w
        Stats.height = h
        return true
    }

    /** Applies new settings or a rotation to the running stream. No-op when nothing relevant changed. */
    @Synchronized
    private fun reconfigure() {
        if (projection == null) return
        val old = settings
        settings = QualityPrefs.load(this)
        val sizeChanged = updateSize()

        val h264 = h264Sink
        val mjpeg = mjpegSink
        val needsRestart = sizeChanged ||
            (h264 != null && settings.fps != old.fps) ||
            (mjpeg != null && (settings.fps != old.fps || settings.jpegQuality != old.jpegQuality))
        if (!needsRestart) return

        detachPipeline()
        // Tell viewers the new size before the first frame at that size arrives.
        val m = meta
        if (sizeChanged && m != null) metaListeners.forEach { it(m) }
        when {
            h264 != null -> launchH264(h264)
            mjpeg != null -> launchMjpeg(mjpeg)
        }
    }

    private fun attach(p: CapturePipeline) {
        val proj = projection ?: return
        val d = display
        if (d == null) {
            display = proj.createVirtualDisplay(
                "lanremote", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, p.surface, null, null
            )
        } else {
            d.resize(width, height, densityDpi)
            d.surface = p.surface
        }
        pipeline = p
    }

    /** Disconnects and stops the current pipeline but keeps the VirtualDisplay alive. */
    private fun detachPipeline() {
        display?.surface = null
        pipeline?.stop()
        pipeline = null
    }

    private fun launchH264(sink: (Boolean, ByteArray) -> Unit) {
        val enc = ScreenEncoder(width, height, settings.fps, StreamQuality.bitrateFor(width, height, settings.fps))
        enc.start(sink)
        attach(enc)
    }

    private fun launchMjpeg(sink: (ByteArray) -> Unit) {
        val cap = JpegCapturer(width, height, settings.jpegQuality, settings.fps)
        cap.start(sink)
        attach(cap)
    }

    @Synchronized
    private fun startH264(sink: (Boolean, ByteArray) -> Unit): (() -> Unit)? {
        if (projection == null) return null
        detachPipeline()
        h264Sink = sink
        mjpegSink = null
        launchH264(sink)
        return { requestKeyframe() }
    }

    @Synchronized
    private fun startMjpeg(sink: (ByteArray) -> Unit) {
        if (projection == null) return
        detachPipeline()
        mjpegSink = sink
        h264Sink = null
        launchMjpeg(sink)
    }

    @Synchronized
    private fun requestKeyframe() {
        (pipeline as? ScreenEncoder)?.requestKeyframe()
    }

    /** Stops the pipeline only if [sink] still owns it, so a stale viewer can't kill a newer one. */
    @Synchronized
    private fun stopPipeline(sink: Any) {
        if (sink !== h264Sink && sink !== mjpegSink) return
        h264Sink = null
        mjpegSink = null
        detachPipeline()
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

    /** Bridges the server (no Service reference) to this instance's pipeline controls. */
    inner class ServiceController {
        fun startH264(sink: (Boolean, ByteArray) -> Unit): (() -> Unit)? =
            this@CaptureService.startH264(sink)
        fun startMjpeg(sink: (ByteArray) -> Unit) = this@CaptureService.startMjpeg(sink)
        fun stopPipeline(sink: Any) = this@CaptureService.stopPipeline(sink)
        fun startAudio(onPacket: (ByteArray) -> Unit): Boolean = this@CaptureService.startAudio(onPacket)
        fun stopAudio() = this@CaptureService.stopAudio()
        fun addMetaListener(l: (VideoMeta) -> Unit) { metaListeners.add(l) }
        fun removeMetaListener(l: (VideoMeta) -> Unit) { metaListeners.remove(l) }
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

        /** Current stream resolution (display size × chosen scale, rotation-aware). */
        @Volatile var meta: VideoMeta? = null

        /** Pipeline control surface for the server; null when not sharing. */
        @Volatile var controller: ServiceController? = null
    }
}
