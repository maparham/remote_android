# Auto-update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On launch, LAN Remote checks GitHub Releases for a newer APK and lets the user download, verify and install it with one tap.

**Architecture:** A new `com.example.lanremote.update` package holds three independently testable units (`UpdateChecker` for the GitHub API + version comparison, `ApkDownloader` for streaming download + SHA-256, `ApkInstaller` + `InstallResultReceiver` for the `PackageInstaller` session) orchestrated by `UpdateManager`, which drives a banner in `MainActivity`. The release build gains a keystore-based signing config so updates install over previous builds.

**Tech Stack:** Kotlin, `HttpsURLConnection`, kotlinx-coroutines (already transitive via Ktor/activity-ktx), kotlinx-serialization-json, Android `PackageInstaller`, JUnit 4. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-05-auto-update-design.md`

## Global Constraints

- `minSdk 29`, `compileSdk 35`, Kotlin 2.0.21, AGP 8.7.2, JDK 17. Build with `export JAVA_HOME=<jdk17>` if not already set.
- No new Gradle dependencies.
- All new Kotlin lives under `app/src/main/kotlin/com/example/lanremote/update/`; tests under `app/src/test/kotlin/com/example/lanremote/update/`.
- GitHub endpoint: `https://api.github.com/repos/maparham/remote_android/releases/latest`, header `Accept: application/vnd.github+json`, 10 s connect/read timeouts.
- Downloads go to `cacheDir/updates/<asset-name>`; stale files are deleted before each download.
- Version comparison: split on `.`, numeric per segment, missing segments are 0, any non-numeric segment ⇒ equal (nothing offered).
- Banner copy: title `LAN Remote v%1$s is available`, body `You will need to re-enable the accessibility service after updating.`, buttons `Update` / `Later`.
- Failure copy: `Download failed`, `Downloaded file failed verification`.
- Network/API/tag problems are silent (`Log.w` only).
- Run unit tests with `./gradlew :app:testDebugUnitTest`; compile with `./gradlew :app:assembleDebug`.

## Review Focus

1. **Tag `v0.2-beta` or `v1.0rc1`** – non-numeric segment. Expected: no banner. Test in Task 2 (`nonNumericTagIsIgnored`).
2. **Version `0.10` vs `0.9`** – numeric, not lexicographic, ordering. Expected: 0.10 is newer. Test in Task 2 (`comparesSegmentsNumerically`).
3. **Release asset without `digest`** (older GitHub data). Expected: download proceeds, no verification, no crash. Tests in Task 2 (`missingDigestGivesNullSha`) and Task 3 (`skipsVerificationWhenNoDigest`).
4. **Previous download interrupted** leaving a partial APK in the cache. Expected: it is removed before the new download. Test in Task 3 (`clearsStaleFiles`).
5. **GitHub API error (rate limit 403, offline)**. Expected: silent, no banner, no crash. Test in Task 2 (`fetchLatestReturnsNullOnFailure`).

---

### Task 1: Build configuration — BuildConfig, release signing, test options

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.gitignore`

**Interfaces:**
- Produces: `com.example.lanremote.BuildConfig.VERSION_NAME` (generated), used by Task 5. Unit tests may call `android.util.Log` without crashing (`isReturnDefaultValues = true`), relied on by Tasks 2 and 3.

- [ ] **Step 1: Replace `app/build.gradle.kts` with the version below**

```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: read from an untracked keystore.properties at the repo root.
// See README "Releasing". Without it, release builds fall back to the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.containsKey("storeFile")

android {
    namespace = "com.example.lanremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.lanremote"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    testOptions {
        // Lets android.util.Log calls inside tested code return silently in JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: keystore.properties not found; release APK is signed with the debug key " +
                        "and will NOT install over keystore-signed builds."
                )
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Add keystore patterns to `.gitignore`**

Append under the `# Local config` section:

```
keystore.properties
*.jks
*.keystore
```

- [ ] **Step 3: Verify the warning and BuildConfig generation**

Run: `./gradlew :app:assembleRelease 2>&1 | grep -c "keystore.properties not found"`
Expected: `1` (or more; the warning is printed).

Run: `./gradlew :app:assembleDebug && grep VERSION_NAME app/build/generated/source/buildConfig/debug/com/example/lanremote/BuildConfig.java`
Expected: a line containing `VERSION_NAME = "0.1"`.

- [ ] **Step 4: Run existing tests to confirm nothing broke**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts .gitignore
git commit -m "build: enable BuildConfig, release keystore signing, Log-safe unit tests"
```

---

### Task 2: `ReleaseInfo` + `UpdateChecker` (GitHub Releases parsing and version comparison)

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/update/ReleaseInfo.kt`
- Create: `app/src/main/kotlin/com/example/lanremote/update/UpdateChecker.kt`
- Test: `app/src/test/kotlin/com/example/lanremote/update/UpdateCheckerTest.kt`

**Interfaces:**
- Produces:
  - `data class ReleaseInfo(versionName: String, apkUrl: String, apkName: String, sizeBytes: Long, sha256: String?)`
  - `class UpdateChecker(releasesUrl: String = DEFAULT_URL, httpGet: (String) -> String = ::defaultHttpGet)` with `suspend fun fetchLatest(): ReleaseInfo?`
  - `UpdateChecker.parseRelease(body: String): ReleaseInfo?`, `UpdateChecker.compareVersions(a: String, b: String): Int`, `UpdateChecker.isNewer(latest: ReleaseInfo, installed: String): Boolean` (companion functions)

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.example.lanremote.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private val sample = """
        {"tag_name":"v0.2","prerelease":false,"assets":[
          {"name":"notes.txt","size":10,"digest":null,"browser_download_url":"https://x/notes.txt"},
          {"name":"lan-remote-0.2.apk","size":16289599,"digest":"sha256:2745F2E3",
           "browser_download_url":"https://x/lan-remote-0.2.apk"}
        ]}
    """.trimIndent()

    @Test fun parsesReleaseAndPicksApkAsset() {
        val r = UpdateChecker.parseRelease(sample)!!
        assertEquals("0.2", r.versionName)
        assertEquals("lan-remote-0.2.apk", r.apkName)
        assertEquals("https://x/lan-remote-0.2.apk", r.apkUrl)
        assertEquals(16289599L, r.sizeBytes)
        assertEquals("2745f2e3", r.sha256) // lower-cased, prefix stripped
    }

    @Test fun missingDigestGivesNullSha() {
        val body = """{"tag_name":"v0.2","prerelease":false,"assets":[
            {"name":"a.apk","size":1,"browser_download_url":"https://x/a.apk"}]}"""
        assertNull(UpdateChecker.parseRelease(body)!!.sha256)
    }

    @Test fun prereleaseIsIgnored() {
        assertNull(UpdateChecker.parseRelease(sample.replace("\"prerelease\":false", "\"prerelease\":true")))
    }

    @Test fun noApkAssetIsIgnored() {
        val body = """{"tag_name":"v0.2","prerelease":false,"assets":[
            {"name":"notes.txt","size":1,"browser_download_url":"https://x/n"}]}"""
        assertNull(UpdateChecker.parseRelease(body))
    }

    @Test fun nonNumericTagIsIgnored() {
        assertNull(UpdateChecker.parseRelease(sample.replace("v0.2", "v0.2-beta")))
        assertNull(UpdateChecker.parseRelease(sample.replace("v0.2", "v1.0rc1")))
    }

    @Test fun garbageIsIgnored() {
        assertNull(UpdateChecker.parseRelease("not json"))
        assertNull(UpdateChecker.parseRelease("[]"))
        assertNull(UpdateChecker.parseRelease("""{"assets":[]}"""))
    }

    @Test fun comparesSegmentsNumerically() {
        assertTrue(UpdateChecker.compareVersions("0.10", "0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("0.9", "0.10") < 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
        assertTrue(UpdateChecker.compareVersions("1.0.1", "1.0") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.x", "1.0"))
    }

    @Test fun isNewerOnlyForStrictlyGreater() {
        val r = UpdateChecker.parseRelease(sample)!!
        assertTrue(UpdateChecker.isNewer(r, "0.1"))
        assertFalse(UpdateChecker.isNewer(r, "0.2"))
        assertFalse(UpdateChecker.isNewer(r, "0.3"))
    }

    @Test fun fetchLatestReturnsNullOnFailure() = runBlocking<Unit> {
        val c = UpdateChecker(httpGet = { throw IOException("HTTP 403") })
        assertNull(c.fetchLatest())
    }

    @Test fun fetchLatestParsesBody() = runBlocking<Unit> {
        val c = UpdateChecker(httpGet = { sample })
        assertEquals("0.2", c.fetchLatest()!!.versionName)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.lanremote.update.UpdateCheckerTest'`
Expected: compilation FAILS with unresolved reference `UpdateChecker`.

- [ ] **Step 3: Create `ReleaseInfo.kt`**

```kotlin
package com.example.lanremote.update

/** A GitHub release that ships an APK. [sha256] is lower-case hex, or null if GitHub gave no digest. */
data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String?,
)
```

- [ ] **Step 4: Create `UpdateChecker.kt`**

```kotlin
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.lanremote.update.UpdateCheckerTest'`
Expected: BUILD SUCCESSFUL, 10 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/lanremote/update/ReleaseInfo.kt \
        app/src/main/kotlin/com/example/lanremote/update/UpdateChecker.kt \
        app/src/test/kotlin/com/example/lanremote/update/UpdateCheckerTest.kt
git commit -m "feat(update): GitHub release lookup and version comparison"
```

---

### Task 3: `ApkDownloader` (streaming download with SHA-256 verification)

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/update/ApkDownloader.kt`
- Test: `app/src/test/kotlin/com/example/lanremote/update/ApkDownloaderTest.kt`

**Interfaces:**
- Produces:
  - `class DigestMismatchException(expected: String, actual: String) : IOException`
  - `object ApkDownloader { suspend fun download(url: String, dir: File, fileName: String, expectedSha256: String?, onProgress: (Int) -> Unit = {}): File; fun cacheDir(context: Context): File }`
  - `onProgress` is called with 0..100 on the IO thread; with unknown content length only `100` at the end.

- [ ] **Step 1: Write the failing tests**

Tests use `file://` URLs, which `URL.openConnection()` serves through `FileURLConnection` with a real content length, so the streaming and hashing paths run unchanged.

```kotlin
package com.example.lanremote.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val data = ByteArray(300_000) { (it % 251).toByte() }

    private fun sourceUrl(bytes: ByteArray): String {
        val f = tmp.newFile("src.bin")
        f.writeBytes(bytes)
        return f.toURI().toURL().toString()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun downloadsVerifiesAndReportsProgress() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        val progress = mutableListOf<Int>()
        val out = ApkDownloader.download(sourceUrl(data), dir, "app.apk", sha256(data)) { progress += it }
        assertEquals(File(dir, "app.apk"), out)
        assertArrayEquals(data, out.readBytes())
        assertEquals(100, progress.last())
        assertTrue(progress.first() < 100)
        assertEquals(progress, progress.sorted())
    }

    @Test fun skipsVerificationWhenNoDigest() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        val out = ApkDownloader.download(sourceUrl(data), dir, "app.apk", null)
        assertArrayEquals(data, out.readBytes())
    }

    @Test fun acceptsUpperCaseDigest() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        ApkDownloader.download(sourceUrl(data), dir, "app.apk", sha256(data).uppercase())
        assertTrue(File(dir, "app.apk").exists())
    }

    @Test fun rejectsWrongDigestAndDeletesFile() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        try {
            ApkDownloader.download(sourceUrl(data), dir, "app.apk", "00".repeat(32))
            fail("expected DigestMismatchException")
        } catch (e: DigestMismatchException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertFalse(File(dir, "app.apk").exists())
    }

    @Test fun clearsStaleFiles() = runBlocking<Unit> {
        val dir = tmp.newFolder("updates")
        File(dir, "old-partial.apk").writeText("junk")
        ApkDownloader.download(sourceUrl(data), dir, "app.apk", null)
        assertFalse(File(dir, "old-partial.apk").exists())
        assertEquals(listOf("app.apk"), dir.list()!!.toList())
    }

    @Test fun createsDirectoryIfMissing() = runBlocking<Unit> {
        val dir = File(tmp.root, "missing/updates")
        val out = ApkDownloader.download(sourceUrl(data), dir, "app.apk", null)
        assertTrue(out.exists())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.lanremote.update.ApkDownloaderTest'`
Expected: compilation FAILS with unresolved reference `ApkDownloader`.

- [ ] **Step 3: Create `ApkDownloader.kt`**

```kotlin
package com.example.lanremote.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.lanremote.update.ApkDownloaderTest'`
Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/lanremote/update/ApkDownloader.kt \
        app/src/test/kotlin/com/example/lanremote/update/ApkDownloaderTest.kt
git commit -m "feat(update): streaming APK download with SHA-256 verification"
```

---

### Task 4: `ApkInstaller` + `InstallResultReceiver` + manifest

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/update/ApkInstaller.kt`
- Create: `app/src/main/kotlin/com/example/lanremote/update/InstallResultReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ApkDownloader.cacheDir(context)` from Task 3.
- Produces: `object ApkInstaller { fun install(context: Context, apk: File) }` — blocking file I/O; callers run it off the main thread.

This task has no JVM unit test: it is a thin wrapper over Android framework classes. Verification is compilation plus the manual end-to-end test in Task 6.

- [ ] **Step 1: Create `ApkInstaller.kt`**

```kotlin
package com.example.lanremote.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/** Hands a downloaded APK to the system installer through a PackageInstaller session. */
object ApkInstaller {
    const val ACTION_INSTALL_RESULT = "com.example.lanremote.INSTALL_RESULT"

    /** Blocking: copies [apk] into a session and commits it. The OS then shows its confirmation UI. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("lanremote.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable
            )
            session.commit(pending.intentSender)
        }
    }
}
```

- [ ] **Step 2: Create `InstallResultReceiver.kt`**

```kotlin
package com.example.lanremote.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Receives PackageInstaller session status; launches the system confirmation when asked. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                ApkDownloader.cacheDir(context).deleteRecursively()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // User cancelled the system dialog: nothing to do; the banner stays.
                Log.i(TAG, "install cancelled by user")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w(TAG, "install failed: $msg")
                Toast.makeText(context, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object { const val TAG = "InstallResultReceiver" }
}
```

- [ ] **Step 3: Update `AndroidManifest.xml`**

Add after the `RECORD_AUDIO` permission:

```xml
    <!-- Lets the app hand a downloaded update APK to the system installer. -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

Add inside `<application>`, after the `CaptureService` `<service>` element:

```xml
        <receiver
            android:name=".update.InstallResultReceiver"
            android:exported="false" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/lanremote/update/ApkInstaller.kt \
        app/src/main/kotlin/com/example/lanremote/update/InstallResultReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(update): PackageInstaller session and result receiver"
```

---

### Task 5: `UpdateManager`, banner layout, and `MainActivity` wiring

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/update/UpdateState.kt`
- Create: `app/src/main/kotlin/com/example/lanremote/update/UpdateManager.kt`
- Create: `app/src/main/res/layout/update_banner.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/activity_main.xml` (insert after the `status` TextView, before the `controlWarn` card)
- Modify: `app/src/main/kotlin/com/example/lanremote/MainActivity.kt`

**Interfaces:**
- Consumes: `UpdateChecker.fetchLatest()`, `UpdateChecker.isNewer()` (Task 2); `ApkDownloader.download()`, `ApkDownloader.cacheDir()`, `DigestMismatchException` (Task 3); `ApkInstaller.install()` (Task 4); `BuildConfig.VERSION_NAME` (Task 1).
- Produces: `sealed class UpdateState`; `class UpdateManager(activity, scope, onState)` with `checkForUpdate()`, `update()`, `dismiss()`, `onResume()`.

- [ ] **Step 1: Create `UpdateState.kt`**

```kotlin
package com.example.lanremote.update

/** Banner state. [info] is the release being offered, or null when nothing is shown. */
sealed class UpdateState {
    abstract val info: ReleaseInfo?

    object Idle : UpdateState() {
        override val info: ReleaseInfo? get() = null
    }
    data class Available(override val info: ReleaseInfo) : UpdateState()
    data class Downloading(override val info: ReleaseInfo, val percent: Int) : UpdateState()
    data class Installing(override val info: ReleaseInfo) : UpdateState()
    data class Failed(override val info: ReleaseInfo, val message: String) : UpdateState()
}
```

- [ ] **Step 2: Create `UpdateManager.kt`**

```kotlin
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
```

- [ ] **Step 3: Add strings to `app/src/main/res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">LAN Remote</string>
    <string name="update_available">LAN Remote v%1$s is available</string>
    <string name="update_reenable_hint">You will need to re-enable the accessibility service after updating.</string>
    <string name="update_action">Update</string>
    <string name="update_later">Later</string>
    <string name="update_downloading">Downloading… %1$d%%</string>
    <string name="update_installing">Opening installer…</string>
</resources>
```

- [ ] **Step 4: Create `app/src/main/res/layout/update_banner.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/updateCard"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="16dp"
    android:visibility="gone"
    app:cardBackgroundColor="#1E2A3F"
    app:cardCornerRadius="16dp"
    app:cardElevation="0dp"
    app:strokeColor="@color/accent_soft"
    app:strokeWidth="1dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="18dp">

        <TextView
            android:id="@+id/updateTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="@color/accent_soft"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/updateMessage"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:text="@string/update_reenable_hint"
            android:textColor="@color/text_secondary"
            android:textSize="13sp" />

        <com.google.android.material.progressindicator.LinearProgressIndicator
            android:id="@+id/updateProgress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:visibility="gone"
            app:indicatorColor="@color/accent"
            app:trackColor="@color/card_stroke" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/updateBtn"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/update_action"
                android:textColor="#FFFFFF"
                android:textStyle="bold"
                app:cornerRadius="12dp"
                app:backgroundTint="@color/accent" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/laterBtn"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="10dp"
                android:text="@string/update_later"
                android:textColor="@color/text_primary"
                app:cornerRadius="12dp"
                app:backgroundTint="@color/card_stroke" />
        </LinearLayout>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

- [ ] **Step 5: Include the banner in `activity_main.xml`**

Insert directly after the closing tag of the `status` TextView (the one with `android:id="@+id/status"`) and before the `<!-- Control-disabled warning` comment:

```xml
        <!-- Update banner (shown only when a newer GitHub release exists) -->
        <include
            android:id="@+id/updateBanner"
            layout="@layout/update_banner" />
```

- [ ] **Step 6: Wire `MainActivity.kt`**

Add imports:

```kotlin
import androidx.lifecycle.lifecycleScope
import com.example.lanremote.update.UpdateManager
import com.example.lanremote.update.UpdateState
```

Add a field after `private var sharing = false`:

```kotlin
    private lateinit var updates: UpdateManager
```

In `onCreate`, after `b.shareBtn.setOnClickListener { shareLink() }` and before `renderIdle()`:

```kotlin
        updates = UpdateManager(this, lifecycleScope, ::renderUpdate)
        b.updateBanner.updateBtn.setOnClickListener { updates.update() }
        b.updateBanner.laterBtn.setOnClickListener { updates.dismiss() }
        updates.checkForUpdate()
```

In `onResume`, after `refreshControlState()`:

```kotlin
        updates.onResume()
```

Add this method after `refreshControlState()`:

```kotlin
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
```

- [ ] **Step 7: Compile and run all unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. If view binding reports `updateBanner` as a `View` rather than `UpdateBannerBinding`, check that the `<include>` carries `android:id="@+id/updateBanner"`.

- [ ] **Step 8: Smoke test on a device (banner appears for an older install)**

Temporarily edit `versionName = "0.0"` in `app/build.gradle.kts`, run `./gradlew :app:installDebug`, open the app. Expected: within a few seconds the banner shows "LAN Remote v0.1 is available". Tap **Later**: banner hides. Relaunch: banner returns. Revert `versionName` to `"0.1"` before committing.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/example/lanremote/update/UpdateState.kt \
        app/src/main/kotlin/com/example/lanremote/update/UpdateManager.kt \
        app/src/main/res/layout/update_banner.xml \
        app/src/main/res/layout/activity_main.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/kotlin/com/example/lanremote/MainActivity.kt
git commit -m "feat(update): on-launch update check with one-tap download and install"
```

---

### Task 6: Release documentation and end-to-end verification

**Files:**
- Modify: `README.md` (sections "One-time device setup", "Project layout", new "Releasing")

**Interfaces:** none (docs + manual verification).

- [ ] **Step 1: Add a "Releasing" section to `README.md`**

Insert before `## Project layout`:

````markdown
## Updating

On launch the app asks GitHub for the latest release. If it is newer than the
installed version, a banner offers **Update**: the APK is downloaded, its SHA-256
is checked against the release asset digest, and the system installer opens.
Android always asks for confirmation; there is no silent install for sideloaded
apps. The first time, Android will also ask you to allow LAN Remote to install
unknown apps.

> Updating disables the accessibility service (an Android security measure);
> re-enable it afterwards. The app reminds you.

## Releasing

Updates only install over an existing app if both are signed with the **same
key**, so releases must use a dedicated keystore, not the per-machine debug key.

1. **Create the keystore once** and keep it safe (losing it means users must
   uninstall to take the next update):

   ```bash
   keytool -genkeypair -v -keystore ~/keys/lanremote-release.jks -alias lanremote \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. **Create `keystore.properties`** in the repo root (git-ignored):

   ```
   storeFile=/Users/you/keys/lanremote-release.jks
   storePassword=…
   keyAlias=lanremote
   keyPassword=…
   ```

   Without this file `assembleRelease` falls back to the debug key and prints a
   warning. Never publish such a build.

3. **Bump the version** in `app/build.gradle.kts`: `versionCode` +1,
   `versionName` to `X.Y` or `X.Y.Z`. The update check compares the release tag
   against `versionName`, so they must match exactly.

4. **Build, tag and publish:**

   ```bash
   ./gradlew :app:assembleRelease
   cp app/build/outputs/apk/release/app-release.apk lan-remote-<version>.apk
   git tag v<version> && git push origin v<version>
   gh release create v<version> lan-remote-<version>.apk --title "LAN Remote v<version>" --notes "..."
   ```

   Only the first `.apk` asset of the latest non-prerelease release is offered to
   users. Mark test builds as pre-release so they are skipped.

> **v0.1 note:** v0.1 was signed with a debug key. The first keystore-signed
> release cannot install over it; say so in that release's notes and ask users to
> uninstall once.
````

- [ ] **Step 2: Update the "Project layout" list in `README.md`**

Add after the `server/` bullet:

```markdown
- `update/` — `UpdateChecker` (GitHub Releases + version compare), `ApkDownloader`
  (streaming download + SHA-256), `ApkInstaller`/`InstallResultReceiver`
  (PackageInstaller session), `UpdateManager` (flow + banner state).
```

- [ ] **Step 3: Update the "Tests" sentence in `README.md`**

Replace `Covers control-event parsing/validation, coordinate mapping, and NAL framing.` with:

```markdown
Covers control-event parsing/validation, coordinate mapping, NAL framing, GitHub
release parsing and version comparison, and APK download/verification.
```

- [ ] **Step 4: Commit the docs**

```bash
git add README.md
git commit -m "docs: updating and releasing sections"
```

- [ ] **Step 5: End-to-end verification (manual, requires a device and GitHub access)**

1. Create the release keystore and `keystore.properties` as documented.
2. Build and install a keystore-signed baseline: keep `versionName = "0.1"`, run `./gradlew :app:assembleRelease` and `adb install -r app/build/outputs/apk/release/app-release.apk` (uninstall the debug-signed app first if adb reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
3. Set `versionCode = 2`, `versionName = "0.1.1"`, `./gradlew :app:assembleRelease`, and publish `gh release create v0.1.1 app/build/outputs/apk/release/app-release.apk --title "test" --notes "test"` (a real, non-draft release).
4. Open the app on the device. Expected: banner "LAN Remote v0.1.1 is available".
5. Tap **Update**. Expected: the unknown-sources settings page opens (first time only); after allowing and returning, the download progress runs, then the system install dialog appears.
6. Confirm. Expected: app installs; relaunch shows no banner; Settings → Apps shows version 0.1.1.
7. Clean up: `gh release delete v0.1.1 --yes --cleanup-tag`, and revert the version bump in `app/build.gradle.kts` (do not commit it).

- [ ] **Step 6: Run the full unit test suite one last time**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.
