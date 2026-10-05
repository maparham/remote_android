package com.example.lanremote.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.example.lanremote.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the update flow for [MainActivity]: check on launch, download + verify, hand to installer.
 * [scope] must dispatch on the main thread (use `lifecycleScope`); [onState] is called on it.
 */
class UpdateManager(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val onState: (UpdateState) -> Unit,
    private val installedVersion: String = BuildConfig.VERSION_NAME,
    private val checker: UpdateChecker = UpdateChecker(),
) {
    var state: UpdateState = UpdateState.Idle
        private set
    private var dismissed = false
    private var resumeAfterPermission = false

    /** Fetch the latest release in the background; shows the banner only if it is newer. */
    fun checkForUpdate() {
        scope.launch {
            val info = checker.fetchLatest() ?: return@launch
            if (!dismissed && UpdateChecker.isNewer(info, installedVersion)) {
                set(UpdateState.Available(info))
            }
        }
    }

    /** "Later": hide until the next process launch. */
    fun dismiss() {
        dismissed = true
        set(UpdateState.Idle)
    }

    /** "Update": ensure install permission, then download, verify and install. */
    fun update() {
        val info = state.info ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            resumeAfterPermission = true
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        scope.launch {
            set(UpdateState.Downloading(info, 0))
            try {
                val file = ApkDownloader.download(
                    info.apkUrl, ApkDownloader.cacheDir(activity), info.apkName, info.sha256
                ) { pct -> scope.launch { set(UpdateState.Downloading(info, pct)) } }
                set(UpdateState.Installing(info))
                withContext(Dispatchers.IO) { ApkInstaller.install(activity, file) }
            } catch (e: DigestMismatchException) {
                Log.w(TAG, "verification failed: $e")
                set(UpdateState.Failed(info, "Downloaded file failed verification"))
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $e")
                set(UpdateState.Failed(info, "Download failed"))
            }
        }
    }

    /** Call from Activity.onResume: continues after the permission screen; resets after installer dismissal. */
    fun onResume() {
        if (resumeAfterPermission && activity.packageManager.canRequestPackageInstalls()) {
            resumeAfterPermission = false
            update()
            return
        }
        val s = state
        if (s is UpdateState.Installing) set(UpdateState.Available(s.info))
    }

    private fun set(s: UpdateState) {
        state = s
        onState(s)
    }

    private companion object { const val TAG = "UpdateManager" }
}
