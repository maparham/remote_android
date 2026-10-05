package com.example.lanremote

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionConfig
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.lanremote.databinding.ActivityMainBinding
import com.example.lanremote.server.NetworkUtil
import com.example.lanremote.server.RemoteServer
import com.example.lanremote.video.CaptureService
import com.example.lanremote.video.QualityPrefs
import com.example.lanremote.video.StreamSettings
import com.google.android.material.button.MaterialButtonToggleGroup
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var server: RemoteServer? = null
    private var sharing = false
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
        b.langBtn.setOnClickListener { switchLanguage() }
        setupQualityPicker()
        renderIdle()
    }

    /**
     * Toggles between English and Persian. AppCompat persists the choice and recreates the
     * activity, which would stop an active share — so the button is disabled while sharing.
     */
    private fun switchLanguage() {
        val target = getString(R.string.lang_switch_target)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(target))
    }

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    /** True while the pickers are being updated from prefs, so that doesn't echo back as a save. */
    private var syncingPicker = false

    private val scaleButtons: Map<Int, Int>
        get() = mapOf(100 to b.scale100.id, 75 to b.scale75.id, 50 to b.scale50.id)
    private val fpsButtons: Map<Int, Int>
        get() = mapOf(15 to b.fps15.id, 30 to b.fps30.id, 60 to b.fps60.id)
    private val jpegButtons: Map<Int, Int>
        get() = mapOf(40 to b.jpegLow.id, 55 to b.jpegMed.id, 75 to b.jpegHigh.id)

    /**
     * Stream quality pickers. Changes are saved immediately and the capture service applies
     * them live. Changes made from a browser show up here through the prefs listener.
     */
    private fun setupQualityPicker() {
        renderSettings(QualityPrefs.load(this))
        bindPicker(b.scaleGroup, { scaleButtons }) { s, v -> s.copy(scalePercent = v) }
        bindPicker(b.fpsGroup, { fpsButtons }) { s, v -> s.copy(fps = v) }
        bindPicker(b.jpegGroup, { jpegButtons }) { s, v -> s.copy(jpegQuality = v) }
        prefsListener = QualityPrefs.listen(this) { renderSettings(it) }
    }

    private fun bindPicker(
        group: MaterialButtonToggleGroup,
        options: () -> Map<Int, Int>,
        apply: (StreamSettings, Int) -> StreamSettings
    ) {
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncingPicker) return@addOnButtonCheckedListener
            val value = options().entries.firstOrNull { it.value == checkedId }?.key
                ?: return@addOnButtonCheckedListener
            QualityPrefs.save(this, apply(QualityPrefs.load(this), value))
        }
    }

    private fun renderSettings(s: StreamSettings) {
        syncingPicker = true
        scaleButtons[s.scalePercent]?.let { b.scaleGroup.check(it) }
        fpsButtons[s.fps]?.let { b.fpsGroup.check(it) }
        jpegButtons[s.jpegQuality]?.let { b.jpegGroup.check(it) }
        syncingPicker = false
    }

    override fun onResume() {
        super.onResume()
        refreshControlState()
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

    private fun requestConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+: ask for the whole display so the consent dialog skips the
        // "Share one app / Share entire screen" choice. Remote control needs the full screen,
        // and users who can't read the dialog's language shouldn't have to pick an option.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
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
            b.warn.setText(R.string.not_on_lan)
        }

        // QR of the URL.
        val qr = QrGenerator.encode(url, 480)
        if (qr != null) {
            b.qr.setImageBitmap(qr)
            b.qr.visibility = android.view.View.VISIBLE
            b.qrHint.visibility = android.view.View.VISIBLE
        }

        b.statsCard.alpha = 1f
        b.toggle.setText(R.string.stop_sharing)
        b.langBtn.isEnabled = false
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
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, url))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    private fun renderIdle() {
        b.status.setText(R.string.status_idle)
        b.status.setTextColor(getColor(R.color.idle))
        b.url.text = "—"
        currentUrl = null
        b.warn.visibility = android.view.View.GONE
        b.qr.visibility = android.view.View.GONE
        b.qrHint.visibility = android.view.View.GONE
        b.shareBtn.visibility = android.view.View.GONE
        b.statsCard.alpha = 0.5f
        b.toggle.setText(R.string.start_sharing)
        b.langBtn.isEnabled = true
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
        b.status.text = getString(R.string.status_sharing, formatDuration(elapsed))
        b.status.setTextColor(getColor(R.color.ok))

        val connected = Stats.viewerConnected
        b.statViewer.setText(if (connected) R.string.viewer_connected else R.string.viewer_waiting)
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

        b.statAudio.setText(if (Stats.audioActive) R.string.audio_on else R.string.audio_off)
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
        prefsListener?.let { QualityPrefs.unlisten(this, it) }
        ui.removeCallbacks(poller)
        if (sharing) stopSharing()
        super.onDestroy()
    }
}
