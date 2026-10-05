package com.example.lanremote.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.IOException

class UpdateManagerTest {
    private val release = ReleaseInfo("0.2", "https://x/a.apk", "a.apk", 1, null)
    private var fetches = 0
    private var downloads = 0
    private var installs = 0
    private var downloadError: Exception? = null
    private var installError: Exception? = null
    private var latest: ReleaseInfo? = release
    private var downloadGate: CompletableDeferred<Unit>? = null // set to pause mid-download

    // Unconfined runs each launch inline, so the flow is synchronous in tests.
    private fun manager() = UpdateManager(
        scope = CoroutineScope(Dispatchers.Unconfined),
        installedVersion = "0.1",
        fetchLatest = { fetches++; latest },
        download = { _, progress ->
            downloads++
            downloadError?.let { throw it }
            progress(50)
            downloadGate?.await()
            File("a.apk")
        },
        install = { installs++; installError?.let { throw it } },
    )

    /** Records every state an observer sees. */
    private class Recorder {
        val seen = mutableListOf<UpdateState>()
        val listener: (UpdateState) -> Unit = { seen += it }
    }

    @Test fun showsBannerWhenNewer() {
        val m = manager()
        val r = Recorder()
        m.observe(r.listener)
        m.checkForUpdate()
        assertEquals(UpdateState.Available(release), r.seen.last())
    }

    @Test fun laterSurvivesActivityRecreation() {
        val m = manager()
        m.observe(Recorder().listener)
        m.checkForUpdate()
        m.dismiss()
        // A recreated Activity observes again and calls checkForUpdate again.
        val second = Recorder()
        m.observe(second.listener)
        m.checkForUpdate()
        assertEquals(UpdateState.Idle, second.seen.last())
        assertEquals(1, fetches) // no extra GitHub API call per recreation
    }

    @Test fun newObserverReceivesCurrentStateImmediately() {
        val m = manager()
        m.checkForUpdate()
        val r = Recorder()
        m.observe(r.listener)
        assertEquals(listOf<UpdateState>(UpdateState.Available(release)), r.seen)
    }

    @Test fun removedObserverGetsNoUpdates() {
        val m = manager()
        val r = Recorder()
        m.observe(r.listener)
        m.removeObserver(r.listener)
        m.checkForUpdate()
        assertEquals(listOf<UpdateState>(UpdateState.Idle), r.seen)
    }

    @Test fun updateShowsProgressThenInstalls() {
        val gate = CompletableDeferred<Unit>().also { downloadGate = it }
        val m = manager()
        m.checkForUpdate()
        m.update()
        assertEquals(UpdateState.Downloading(release, 50), m.state) // mid-download
        assertEquals(0, installs)
        gate.complete(Unit)
        assertEquals(UpdateState.Installing(release), m.state)
        assertEquals(1, installs)
    }

    @Test fun lateProgressDoesNotOverwriteInstalling() {
        val m = manager()
        m.checkForUpdate()
        m.update() // the fake posts progress(50) without pausing, so it lands after the download ends
        assertEquals(UpdateState.Installing(release), m.state)
    }

    @Test fun secondTapWhileBusyDoesNotDownloadAgain() {
        val m = manager()
        m.checkForUpdate()
        m.update()
        m.update()
        assertEquals(1, downloads)
    }

    @Test fun permissionRoundTripContinuesAfterRecreation() {
        val m = manager()
        m.checkForUpdate()
        m.awaitPermission()
        m.onResume(canInstall = false) // user came back without granting
        assertEquals(0, downloads)
        m.observe(Recorder().listener) // Activity recreated while in settings
        m.onResume(canInstall = true)
        assertEquals(1, downloads)
    }

    @Test fun installerDismissedReturnsToAvailable() {
        val m = manager()
        m.checkForUpdate()
        m.update()
        m.onResume(canInstall = true)
        assertEquals(UpdateState.Available(release), m.state)
    }

    @Test fun downloadErrorShowsFailed() {
        downloadError = IOException("boom")
        val m = manager()
        m.checkForUpdate()
        m.update()
        assertEquals(UpdateState.Failed(release, FailReason.DOWNLOAD), m.state)
        assertFalse(installs > 0)
    }

    @Test fun digestMismatchShowsVerificationFailure() {
        downloadError = DigestMismatchException("aa", "bb")
        val m = manager()
        m.checkForUpdate()
        m.update()
        assertEquals(UpdateState.Failed(release, FailReason.VERIFY), m.state)
    }

    @Test fun retryAfterFailureDownloadsAgain() {
        downloadError = IOException("boom")
        val m = manager()
        m.checkForUpdate()
        m.update()
        downloadError = null
        m.update()
        assertEquals(2, downloads)
        assertEquals(UpdateState.Installing(release), m.state)
    }

    @Test fun installErrorIsNotReportedAsDownloadFailure() {
        installError = IOException("no space")
        val m = manager()
        m.checkForUpdate()
        m.update()
        assertEquals(UpdateState.Failed(release, FailReason.INSTALL), m.state)
    }

    @Test fun failedCheckIsRetriedOnNextCall() {
        latest = null // offline at launch
        val m = manager()
        m.checkForUpdate()
        assertEquals(UpdateState.Idle, m.state)
        latest = release
        m.checkForUpdate()
        assertEquals(UpdateState.Available(release), m.state)
        m.checkForUpdate()
        assertEquals(2, fetches) // no more fetches once one succeeded
    }

    @Test fun installerFailureShowsInBanner() {
        val m = manager()
        m.checkForUpdate()
        m.update()
        m.onInstallFailed("INSTALL_FAILED_UPDATE_INCOMPATIBLE")
        assertEquals(UpdateState.Failed(release, FailReason.INSTALL, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"), m.state)
    }

    @Test fun installerResultIgnoredWhenNotInstalling() {
        val m = manager()
        m.checkForUpdate()
        m.onInstallFailed("x") // e.g. a fresh process that never started this install
        m.onInstallCancelled()
        assertEquals(UpdateState.Available(release), m.state)
    }

    @Test fun installerCancelReturnsToAvailable() {
        val m = manager()
        m.checkForUpdate()
        m.update()
        m.onInstallCancelled()
        assertEquals(UpdateState.Available(release), m.state)
    }

    @Test fun confirmLaunchesImmediatelyWhileVisible() {
        val m = manager()
        var launched = 0
        m.onResume(canInstall = true)
        m.onConfirmRequired { launched++ }
        assertEquals(1, launched)
    }

    @Test fun confirmWaitsUntilAppIsVisibleAgain() {
        val m = manager()
        m.checkForUpdate()
        m.onResume(canInstall = true)
        m.update()
        m.onPause() // user switched apps during the download
        var launched = 0
        m.onConfirmRequired { launched++ }
        assertEquals(0, launched) // a background activity start would be blocked
        m.onResume(canInstall = true)
        assertEquals(1, launched)
        assertEquals(UpdateState.Installing(release), m.state) // not reset: the installer is showing
        m.onResume(canInstall = true)
        assertEquals(1, launched)
    }
}
