package com.example.lanremote.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest

class DigestMismatchException(expected: String, actual: String) :
    IOException("SHA-256 mismatch: expected $expected, got $actual")

object ApkDownloader {
    /** `cacheDir/updates` — where downloaded APKs live until installed. */
    fun cacheDir(context: Context): File = File(context.cacheDir, "updates")

    /**
     * Streams [url] into [dir]/[fileName], clearing [dir] first, hashing as it goes.
     * Throws [DigestMismatchException] (file deleted) when [expectedSha256] is set and differs.
     * [onProgress] receives 0..100 on the IO thread; if the length is unknown only 100 at the end.
     */
    suspend fun download(
        url: String,
        dir: File,
        fileName: String,
        expectedSha256: String?,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, fileName)

        val conn = URL(url).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "LAN-Remote-Android")
        }
        val total = conn.contentLengthLong
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastPct = -1

        conn.getInputStream().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive() // stop promptly if the update is cancelled
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        onProgress(100)

        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            dest.delete()
            throw DigestMismatchException(expectedSha256.lowercase(), actual)
        }
        dest
    }
}
