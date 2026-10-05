package com.example.lanremote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.example.lanremote.databinding.ActivityMainBinding
import com.example.lanremote.server.NetworkUtil
import com.example.lanremote.server.RemoteServer
import com.example.lanremote.update.UpdateManager
import com.example.lanremote.update.UpdateState
import com.example.lanremote.video.CaptureService
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var server: RemoteServer? = null
    private var sharing = false
    private lateinit var updates: UpdateManager
    private val port = 8080

    private val ui = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var sessionStart = 0L
    private var lastPollTime = 0L
    private var lastFrames = 0L
    private var lastVideoBytes = 0L

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startSharing(result.resultCode, result.data!!)
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            updateStats()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Dark background → light status/nav bar icons.
        WindowCompat.getInsetsController(window, b.root).let {
            it.isAppearanceLightStatusBars = false
            it.isAppearanceLightNavigationBars = false
        }

        val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 0)

        b.toggle.setOnClickListener { if (sharing) stopSharing() else requestConsent() }
        b.enableControlBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.shareBtn.setOnClickListener { shareLink() }
        updates = UpdateManager(this, lifecycleScope, ::renderUpdate)
        b.updateBanner.updateBtn.setOnClickListener { updates.update() }
        b.updateBanner.laterBtn.setOnClickListener { updates.dismiss() }
        updates.checkForUpdate()
        renderIdle()
    }

    override fun onResume() {
        super.onResume()
        refreshControlState()
        updates.onResume()
    }

    /** True when the LAN Remote AccessibilityService is enabled (required for taps/typing). */
    private fun isControlEnabled(): Boolean {
        val expected = "$packageName/${com.example.lanremote.control.ControlService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun refreshControlState() {
        b.controlWarn.visibility = if (isControlEnabled()) View.GONE else View.VISIBLE
    }

    private fun renderUpdate(s: UpdateState) {
        val ub = b.updateBanner
        val info = s.info
        if (info == null) {
            ub.root.visibility = View.GONE
            return
        }
        ub.root.visibility = View.VISIBLE
        ub.updateTitle.text = getString(R.string.update_available, info.versionName)
        ub.updateProgress.visibility = if (s is UpdateState.Downloading) View.VISIBLE else View.GONE
        when (s) {
            is UpdateState.Downloading -> {
                ub.updateProgress.setProgressCompat(s.percent, true)
                ub.updateMessage.text = getString(R.string.update_downloading, s.percent)
                ub.updateBtn.isEnabled = false
                ub.laterBtn.isEnabled = false
            }
            is UpdateState.Installing -> {
                ub.updateMessage.text = getString(R.string.update_installing)
                ub.updateBtn.isEnabled = false
                ub.laterBtn.isEnabled = false
            }
            is UpdateState.Failed -> {
                ub.updateMessage.text = s.message
                ub.updateBtn.isEnabled = true
                ub.laterBtn.isEnabled = true
            }
            else -> {
                ub.updateMessage.text = getString(R.string.update_reenable_hint)
                ub.updateBtn.isEnabled = true
                ub.laterBtn.isEnabled = true
            }
        }
    }

    private fun requestConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startSharing(code: Int, data: Intent) {
        val svc = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, code)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(svc)
        server = RemoteServer(applicationContext).also { it.start(port) }
        sharing = true
        sessionStart = SystemClock.elapsedRealtime()
        lastPollTime = 0L; lastFrames = 0L; lastVideoBytes = 0L

        val ip = NetworkUtil.lanIpAddress()
        val url = "http://${ip ?: "unknown"}:$port"
        currentUrl = url
        b.url.text = url
        b.shareBtn.visibility = View.VISIBLE

        if (NetworkUtil.isReachableLan(ip)) {
            b.warn.visibility = android.view.View.GONE
        } else {
            b.warn.visibility = android.view.View.VISIBLE
            b.warn.text = "⚠ Not on Wi-Fi/LAN — this looks like a cellular address a browser can't reach. Connect the phone to Wi-Fi."
        }

        // QR of the URL.
        val qr = QrGenerator.encode(url, 480)
        if (qr != null) {
            b.qr.setImageBitmap(qr)
            b.qr.visibility = android.view.View.VISIBLE
            b.qrHint.visibility = android.view.View.VISIBLE
        }

        b.statsCard.alpha = 1f
        b.toggle.text = "Stop sharing"
        ui.post(poller)
    }

    private fun stopSharing() {
        server?.stop(); server = null
        stopService(Intent(this, CaptureService::class.java))
        sharing = false
        ui.removeCallbacks(poller)
        Stats.reset()
        renderIdle()
    }

    private fun shareLink() {
        val url = currentUrl ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LAN Remote link")
            putExtra(
                Intent.EXTRA_TEXT,
                "Control my screen on the same network: $url\n" +
                    "Open it in a browser (works while I'm sharing)."
            )
        }
        startActivity(Intent.createChooser(send, "Share connection link"))
    }

    private fun renderIdle() {
        b.status.text = "● Idle"
        b.status.setTextColor(getColor(R.color.idle))
        b.url.text = "—"
        currentUrl = null
        b.warn.visibility = android.view.View.GONE
        b.qr.visibility = android.view.View.GONE
        b.qrHint.visibility = android.view.View.GONE
        b.shareBtn.visibility = android.view.View.GONE
        b.statsCard.alpha = 0.5f
        b.toggle.text = "Start sharing"
        b.statViewer.text = "—"
        b.statVideo.text = "—"
        b.statFps.text = "—"
        b.statBitrate.text = "—"
        b.statAudio.text = "—"
        b.statData.text = "—"
    }

    private fun updateStats() {
        if (!sharing) return
        val elapsed = (SystemClock.elapsedRealtime() - sessionStart) / 1000
        b.status.text = "● Sharing · ${formatDuration(elapsed)}"
        b.status.setTextColor(getColor(R.color.ok))

        val connected = Stats.viewerConnected
        b.statViewer.text = if (connected) "Connected" else "Waiting…"
        b.statViewer.setTextColor(getColor(if (connected) R.color.ok else R.color.text_secondary))

        b.statVideo.text = if (connected) "${Stats.videoMode} · ${Stats.width}×${Stats.height}" else "—"

        val now = SystemClock.elapsedRealtime()
        val frames = Stats.videoFrames
        val vbytes = Stats.videoBytes
        if (lastPollTime != 0L && connected) {
            val dt = (now - lastPollTime) / 1000.0
            if (dt > 0) {
                val fps = (frames - lastFrames) / dt
                val bps = (vbytes - lastVideoBytes) * 8 / dt
                b.statFps.text = String.format(Locale.US, "%.0f fps", fps)
                b.statBitrate.text = formatBitrate(bps)
            }
        } else {
            b.statFps.text = "—"
            b.statBitrate.text = "—"
        }
        lastPollTime = now
        lastFrames = frames
        lastVideoBytes = vbytes

        b.statAudio.text = if (Stats.audioActive) "On" else "Off"
        b.statAudio.setTextColor(getColor(if (Stats.audioActive) R.color.ok else R.color.text_secondary))

        b.statData.text = formatBytes(Stats.videoBytes + Stats.audioBytes)
    }

    private fun formatDuration(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
        else -> "$bytes B"
    }

    private fun formatBitrate(bps: Double): String = when {
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1e6)
        bps >= 1_000 -> String.format(Locale.US, "%.0f kbps", bps / 1e3)
        else -> String.format(Locale.US, "%.0f bps", bps)
    }

    override fun onDestroy() {
        ui.removeCallbacks(poller)
        if (sharing) stopSharing()
        super.onDestroy()
    }
}
