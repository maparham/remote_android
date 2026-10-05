package com.example.lanremote.update

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * The update flow: check once per process (retried until it succeeds), download + verify,
 * hand to the installer, and surface the installer's result.
 *
 * Lives for the whole process (see [Updates]), not per Activity, so "Later", a running
 * download and a pending permission round-trip survive rotation and other recreation.
 * [scope] must dispatch on the main thread; observers are called on it.
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private val installedVersion: String,
    private val fetchLatest: suspend () -> ReleaseInfo?,
    private val download: suspend (ReleaseInfo, (Int) -> Unit) -> File,
    private val install: suspend (File) -> Unit,
) {
    var state: UpdateState = UpdateState.Idle
        private set
    private var observer: ((UpdateState) -> Unit)? = null
    private var checked = false
    private var checking = false
    private var dismissed = false
    private var awaitingPermission = false
    private var visible = false
    /** The installer's confirmation, held while no Activity is visible to launch it from. */
    private var pendingConfirm: (() -> Unit)? = null

    /** Attach the visible Activity's renderer; it receives the current state immediately. */
    fun observe(listener: (UpdateState) -> Unit) {
        observer = listener
        listener(state)
    }

    /** Detach [listener] if it is still the current observer. */
    fun removeObserver(listener: (UpdateState) -> Unit) {
        if (observer === listener) observer = null
    }

    /**
     * Fetch the latest release; shows the banner only if it is newer. Once a fetch succeeds it is
     * not repeated this process, but a failed one (offline, rate limit) is retried on the next call.
     */
    fun checkForUpdate() {
        if (checked || checking) return
        checking = true
        scope.launch {
            val info = try {
                fetchLatest()
            } finally {
                checking = false
            }
            if (info == null) return@launch
            checked = true
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

    /** The user was sent to the unknown-sources settings screen; continue on return if granted. */
    fun awaitPermission() {
        awaitingPermission = true
    }

    /** "Update" with install permission already granted: download, verify and install. */
    fun update() {
        val info = when (val s = state) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info
            else -> return // idle, or already downloading/installing
        }
        scope.launch {
            set(UpdateState.Downloading(info, 0))
            val file = try {
                download(info) { pct ->
                    // Progress is posted from the IO thread; drop it if the download already ended.
                    scope.launch { if (state is UpdateState.Downloading) set(UpdateState.Downloading(info, pct)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DigestMismatchException) {
                Log.w(TAG, "verification failed: $e")
                set(UpdateState.Failed(info, FailReason.VERIFY))
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "download failed: $e")
                set(UpdateState.Failed(info, FailReason.DOWNLOAD))
                return@launch
            }
            set(UpdateState.Installing(info))
            try {
                install(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "install failed: $e")
                set(UpdateState.Failed(info, FailReason.INSTALL))
            }
        }
    }

    /** Call from Activity.onResume with `packageManager.canRequestPackageInstalls()`. */
    fun onResume(canInstall: Boolean) {
        visible = true
        pendingConfirm?.let {
            // The installer finished staging while we were in the background: show it now.
            pendingConfirm = null
            it()
            return
        }
        if (awaitingPermission) {
            if (canInstall) {
                awaitingPermission = false
                update()
            }
            return
        }
        // Back from the system install dialog without the app being replaced: offer again.
        val s = state
        if (s is UpdateState.Installing) set(UpdateState.Available(s.info))
    }

    /** Call from Activity.onPause. */
    fun onPause() {
        visible = false
    }

    /**
     * The installer needs the user's confirmation. Android blocks activity starts from the
     * background, so [launch] runs now only if the app is visible, else on the next [onResume].
     */
    fun onConfirmRequired(launch: () -> Unit) {
        if (visible) launch() else pendingConfirm = launch
    }

    /** The system installer rejected the APK (signature conflict, storage, invalid file…). */
    fun onInstallFailed(message: String?) {
        val s = state
        if (s is UpdateState.Installing) set(UpdateState.Failed(s.info, FailReason.INSTALL, message))
    }

    /** The user cancelled the system install dialog. */
    fun onInstallCancelled() {
        val s = state
        if (s is UpdateState.Installing) set(UpdateState.Available(s.info))
    }

    private fun set(s: UpdateState) {
        state = s
        observer?.invoke(s)
    }

    private companion object { const val TAG = "UpdateManager" }
}
