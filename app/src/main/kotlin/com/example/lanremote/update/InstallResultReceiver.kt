package com.example.lanremote.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/** Receives PackageInstaller session status and reports it to [UpdateManager] (main thread). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = Updates.get(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val app = context.applicationContext
                updates.onConfirmRequired { app.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                ApkDownloader.cacheDir(context).deleteRecursively()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "install cancelled by user")
                updates.onInstallCancelled()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w(TAG, "install failed: $msg")
                updates.onInstallFailed(msg)
            }
        }
    }

    private companion object { const val TAG = "InstallResultReceiver" }
}
