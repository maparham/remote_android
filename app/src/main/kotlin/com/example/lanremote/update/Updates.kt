package com.example.lanremote.update

import android.content.Context
import com.example.lanremote.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Process-wide [UpdateManager], so update state outlives any single Activity instance. */
object Updates {
    @Volatile private var instance: UpdateManager? = null

    fun get(context: Context): UpdateManager = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): UpdateManager {
        val checker = UpdateChecker()
        return UpdateManager(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            installedVersion = BuildConfig.VERSION_NAME,
            fetchLatest = { checker.fetchLatest() },
            download = { info, progress ->
                ApkDownloader.download(info.apkUrl, ApkDownloader.cacheDir(app), info.apkName, info.sha256, progress)
            },
            install = { file -> withContext(Dispatchers.IO) { ApkInstaller.install(app, file) } },
        )
    }
}
