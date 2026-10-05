package com.example.lanremote.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up the latest GitHub release. [httpGet] is injectable for tests; the default
 * performs a real HTTPS GET.
 */
class UpdateChecker(
    private val releasesUrl: String = DEFAULT_URL,
    private val httpGet: (String) -> String = ::defaultHttpGet,
) {
    /** Latest non-prerelease release with an APK asset, or null on any problem (logged only). */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            parseRelease(httpGet(releasesUrl))
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: $e")
            null
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        const val DEFAULT_URL = "https://api.github.com/repos/maparham/remote_android/releases/latest"
        private val json = Json { ignoreUnknownKeys = true }

        fun parseRelease(body: String): ReleaseInfo? {
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                return null
            }
            if (root["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val version = tag.removePrefix("v")
            if (!isNumericVersion(version)) return null

            val asset = root["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
                ?: return null
            val name = asset["name"]!!.jsonPrimitive.content
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")
                ?.lowercase()
            return ReleaseInfo(version, url, name, size, digest)
        }

        /** Numeric dotted comparison. Any non-numeric segment makes the versions compare equal. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.').map { it.toIntOrNull() ?: return 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: return 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        fun isNewer(latest: ReleaseInfo, installed: String): Boolean =
            compareVersions(latest.versionName, installed) > 0

        private fun isNumericVersion(v: String): Boolean =
            v.isNotEmpty() && v.split('.').all { it.toIntOrNull() != null }

        private fun defaultHttpGet(url: String): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "LAN-Remote-Android")
            try {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().readText()
            } finally {
                c.disconnect()
            }
        }
    }
}
