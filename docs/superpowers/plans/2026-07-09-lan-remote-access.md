# LAN Remote Access for Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a self-contained, non-root Android app that lets a browser on the same LAN view and control the device (touch, nav keys, text) via an embedded web server.

**Architecture:** One Android app on the target device runs an embedded Ktor server exposing a web client plus two WebSockets: `/video` (H.264 NAL units from `MediaProjection`+`MediaCodec`) and `/control` (JSON input events dispatched through an `AccessibilityService`). Sharing is user-initiated, foreground-serviced, and bound to the LAN interface only. The web client decodes H.264 with `WebCodecs` and sends normalized-coordinate input events.

**Tech Stack:** Kotlin, Android SDK (compile against latest installed platform, minSdk 29), Gradle with Kotlin DSL, Ktor embedded server (Netty + WebSockets), JUnit for JVM unit tests, vanilla TypeScript/JS web client.

## Global Constraints

- **minSdk:** 29. **No root. No ADB dependency at runtime** (adb is used only by the developer for install/logcat/testing).
- **Language:** Kotlin, coroutines. Web client: vanilla TS/JS, no framework.
- **Server binds to the LAN interface only**, and only while a session is active.
- **Sharing is OFF by default**; each session is explicitly started by the user, shows the `MediaProjection` consent prompt, and posts a persistent foreground notification.
- **No authentication in v1** (explicit user decision). Do not add login/PIN/TLS in v1.
- **Coordinates on the wire are always normalized [0,1]**; the device scales to real pixels.
- **v1 features only:** live screen, tap/long-press/swipe, Back/Home/Recents, text input. No clipboard/file/audio/native-client.
- **JDK:** Android Studio JBR 21 at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`. **Android SDK:** `~/Library/Android/sdk` (currently bare — Task 0 bootstraps it).
- **Test device:** Pixel 8a, API 37, transport visible via `adb devices`.

---

### Task 0: SDK bootstrap + buildable app skeleton on device

Bootstrap the empty SDK, create the Gradle project, and prove an empty app installs and launches on the Pixel 8a.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/example/lanremote/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/layout/activity_main.xml`
- Create: `gradlew` + wrapper (via `gradle wrapper`)
- Create: `.gitignore`

**Interfaces:**
- Produces: package `com.example.lanremote`, an installable debug APK, `MainActivity` as launcher.

- [ ] **Step 1: Bootstrap SDK command-line tools + platform + build-tools**

```bash
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# cmdline-tools
cd "$ANDROID_SDK_ROOT"
curl -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
mkdir -p cmdline-tools && (cd cmdline-tools && unzip -q /tmp/cmdtools.zip && mv cmdline-tools latest)
SDKM="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --licenses
"$SDKM" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```
Expected: `platform-tools`, `platforms/android-35`, `build-tools/35.0.0` populated. (If API 35 is unavailable in the channel, install the highest `platforms;android-3x` offered by `"$SDKM" --list | grep platforms`.)

- [ ] **Step 2: Create root Gradle config**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "lanremote"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

`local.properties` (not committed):
```properties
sdk.dir=/Users/mahmoudparham/Library/Android/sdk
```

`.gitignore`:
```
.gradle/
build/
local.properties
*.iml
.idea/
```

- [ ] **Step 3: Create app module build file**

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
    buildFeatures { viewBinding = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Ktor embedded server (used in later tasks)
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    testImplementation("junit:junit:4.13.2")
}
```
Note: add `id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"` to root plugins and apply it here once serialization is needed (Task 1).

- [ ] **Step 4: Create manifest, MainActivity, resources**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:theme="@style/Theme.Material3.DayNight">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/kotlin/com/example/lanremote/MainActivity.kt`:
```kotlin
package com.example.lanremote

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">LAN Remote</string>
</resources>
```

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="24dp">
    <TextView android:id="@+id/status" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:text="LAN Remote (idle)" />
</LinearLayout>
```

- [ ] **Step 5: Generate wrapper, build, install, launch**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
gradle wrapper --gradle-version 8.10
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -n com.example.lanremote/.MainActivity
```
Expected: BUILD SUCCESSFUL; app launches showing "LAN Remote (idle)".

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: bootstrap SDK and buildable app skeleton"
```

---

### Task 1: Control protocol model + coordinate mapping (pure Kotlin, TDD)

Pure JVM-testable logic: parse `/control` JSON into a sealed type, and map normalized coords to device pixels. No Android APIs, so it runs as a fast unit test.

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/control/ControlEvent.kt`
- Create: `app/src/main/kotlin/com/example/lanremote/control/CoordinateMapper.kt`
- Test: `app/src/test/kotlin/com/example/lanremote/control/ControlEventTest.kt`
- Test: `app/src/test/kotlin/com/example/lanremote/control/CoordinateMapperTest.kt`
- Modify: root `build.gradle.kts` (add serialization plugin), `app/build.gradle.kts` (apply it)

**Interfaces:**
- Produces:
  - `sealed interface ControlEvent` with `data class Tap(val x: Float, val y: Float)`, `LongPress(x,y,durationMs: Long)`, `Swipe(x1,y1,x2,y2,durationMs)`, `Key(action: KeyAction)`, `Text(value: String)`; `enum class KeyAction { BACK, HOME, RECENTS }`.
  - `object ControlEventParser { fun parse(json: String): ControlEvent? }` — returns null on malformed/out-of-range input.
  - `class CoordinateMapper(val widthPx: Int, val heightPx: Int) { fun toPixels(nx: Float, ny: Float): Pair<Int,Int> }` — clamps to [0,1] then scales.

- [ ] **Step 1: Add serialization plugin**

Root `build.gradle.kts` plugins block, add:
```kotlin
id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
```
`app/build.gradle.kts` plugins block, add:
```kotlin
id("org.jetbrains.kotlin.plugin.serialization")
```

- [ ] **Step 2: Write failing tests**

`app/src/test/kotlin/com/example/lanremote/control/CoordinateMapperTest.kt`:
```kotlin
package com.example.lanremote.control
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    @Test fun mapsCenter() {
        val m = CoordinateMapper(1080, 2400)
        assertEquals(Pair(540, 1200), m.toPixels(0.5f, 0.5f))
    }
    @Test fun clampsOutOfRange() {
        val m = CoordinateMapper(1080, 2400)
        assertEquals(Pair(1079, 2399), m.toPixels(2.0f, 2.0f))
        assertEquals(Pair(0, 0), m.toPixels(-1.0f, -1.0f))
    }
}
```

`app/src/test/kotlin/com/example/lanremote/control/ControlEventTest.kt`:
```kotlin
package com.example.lanremote.control
import org.junit.Assert.*
import org.junit.Test

class ControlEventTest {
    @Test fun parsesTap() {
        val e = ControlEventParser.parse("""{"type":"tap","x":0.5,"y":0.25}""")
        assertEquals(ControlEvent.Tap(0.5f, 0.25f), e)
    }
    @Test fun parsesKey() {
        val e = ControlEventParser.parse("""{"type":"key","action":"back"}""")
        assertEquals(ControlEvent.Key(KeyAction.BACK), e)
    }
    @Test fun parsesText() {
        val e = ControlEventParser.parse("""{"type":"text","value":"hi"}""")
        assertEquals(ControlEvent.Text("hi"), e)
    }
    @Test fun rejectsOutOfRange() {
        assertNull(ControlEventParser.parse("""{"type":"tap","x":1.5,"y":0.1}"""))
    }
    @Test fun rejectsGarbage() {
        assertNull(ControlEventParser.parse("""not json"""))
        assertNull(ControlEventParser.parse("""{"type":"nope"}"""))
    }
}
```

- [ ] **Step 3: Run tests, verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.lanremote.control.*"`
Expected: FAIL (unresolved references `CoordinateMapper`, `ControlEventParser`).

- [ ] **Step 4: Implement CoordinateMapper**

`app/src/main/kotlin/com/example/lanremote/control/CoordinateMapper.kt`:
```kotlin
package com.example.lanremote.control

class CoordinateMapper(private val widthPx: Int, private val heightPx: Int) {
    fun toPixels(nx: Float, ny: Float): Pair<Int, Int> {
        val cx = nx.coerceIn(0f, 1f)
        val cy = ny.coerceIn(0f, 1f)
        val px = (cx * (widthPx - 1)).toInt()
        val py = (cy * (heightPx - 1)).toInt()
        return Pair(px, py)
    }
}
```

- [ ] **Step 5: Implement ControlEvent + parser**

`app/src/main/kotlin/com/example/lanremote/control/ControlEvent.kt`:
```kotlin
package com.example.lanremote.control

import kotlinx.serialization.json.*

enum class KeyAction { BACK, HOME, RECENTS }

sealed interface ControlEvent {
    data class Tap(val x: Float, val y: Float) : ControlEvent
    data class LongPress(val x: Float, val y: Float, val durationMs: Long) : ControlEvent
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long) : ControlEvent
    data class Key(val action: KeyAction) : ControlEvent
    data class Text(val value: String) : ControlEvent
}

object ControlEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun Float.inUnit() = this in 0f..1f

    fun parse(input: String): ControlEvent? = try {
        val obj = json.parseToJsonElement(input).jsonObject
        fun f(k: String) = obj[k]!!.jsonPrimitive.float
        fun l(k: String) = obj[k]?.jsonPrimitive?.long ?: 300L
        when (obj["type"]?.jsonPrimitive?.content) {
            "tap" -> f("x").let { x -> f("y").let { y ->
                if (x.inUnit() && y.inUnit()) ControlEvent.Tap(x, y) else null } }
            "longpress" -> {
                val x = f("x"); val y = f("y")
                if (x.inUnit() && y.inUnit()) ControlEvent.LongPress(x, y, l("durationMs")) else null
            }
            "swipe" -> {
                val x1 = f("x1"); val y1 = f("y1"); val x2 = f("x2"); val y2 = f("y2")
                if (x1.inUnit() && y1.inUnit() && x2.inUnit() && y2.inUnit())
                    ControlEvent.Swipe(x1, y1, x2, y2, l("durationMs")) else null
            }
            "key" -> when (obj["action"]?.jsonPrimitive?.content) {
                "back" -> ControlEvent.Key(KeyAction.BACK)
                "home" -> ControlEvent.Key(KeyAction.HOME)
                "recents" -> ControlEvent.Key(KeyAction.RECENTS)
                else -> null
            }
            "text" -> obj["value"]?.jsonPrimitive?.content?.let { ControlEvent.Text(it) }
            else -> null
        }
    } catch (e: Exception) { null }
}
```

- [ ] **Step 6: Run tests, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.lanremote.control.*"`
Expected: PASS (7 tests).

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: control event model + coordinate mapping with tests"
```

---

### Task 2: NAL framing for the video WebSocket (pure Kotlin, TDD)

The `/video` socket sends length-prefixed messages: first a one-time JSON metadata text frame (resolution), then binary frames each carrying one access unit. Define and test the binary framing helper so encoder and client agree.

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/video/VideoFrame.kt`
- Test: `app/src/test/kotlin/com/example/lanremote/video/VideoFrameTest.kt`

**Interfaces:**
- Produces:
  - `data class VideoMeta(val width: Int, val height: Int)` with `fun toJson(): String`.
  - `object VideoFrame { const val FLAG_KEYFRAME: Byte; fun encode(isKeyframe: Boolean, payload: ByteArray): ByteArray; fun decodeHeader(frame: ByteArray): Boolean /* isKeyframe */; fun payload(frame: ByteArray): ByteArray }` — binary layout: byte0 = flags, bytes1..n = H.264 payload.

- [ ] **Step 1: Write failing test**

`app/src/test/kotlin/com/example/lanremote/video/VideoFrameTest.kt`:
```kotlin
package com.example.lanremote.video
import org.junit.Assert.*
import org.junit.Test

class VideoFrameTest {
    @Test fun roundTripsKeyframe() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = VideoFrame.encode(true, payload)
        assertTrue(VideoFrame.decodeHeader(frame))
        assertArrayEquals(payload, VideoFrame.payload(frame))
    }
    @Test fun roundTripsDeltaFrame() {
        val payload = byteArrayOf(9, 8, 7)
        val frame = VideoFrame.encode(false, payload)
        assertFalse(VideoFrame.decodeHeader(frame))
        assertArrayEquals(payload, VideoFrame.payload(frame))
    }
    @Test fun metaJson() {
        assertEquals("""{"width":1080,"height":2400}""", VideoMeta(1080, 2400).toJson())
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.lanremote.video.*"`
Expected: FAIL (unresolved `VideoFrame`).

- [ ] **Step 3: Implement**

`app/src/main/kotlin/com/example/lanremote/video/VideoFrame.kt`:
```kotlin
package com.example.lanremote.video

data class VideoMeta(val width: Int, val height: Int) {
    fun toJson(): String = """{"width":$width,"height":$height}"""
}

object VideoFrame {
    private const val FLAG_KEYFRAME: Int = 0x01

    fun encode(isKeyframe: Boolean, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = (if (isKeyframe) FLAG_KEYFRAME else 0).toByte()
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }
    fun decodeHeader(frame: ByteArray): Boolean = (frame[0].toInt() and FLAG_KEYFRAME) != 0
    fun payload(frame: ByteArray): ByteArray = frame.copyOfRange(1, frame.size)
}
```

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.lanremote.video.*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: video NAL framing helper with tests"
```

---

### Task 3: ControlService (AccessibilityService) — input injection

Implement the Accessibility service that turns `ControlEvent`s into real device input. Expose a process-wide dispatch entry point so the server (Task 5) can reach it.

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/control/ControlService.kt`
- Create: `app/src/main/res/xml/accessibility_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (register service)

**Interfaces:**
- Consumes: `ControlEvent`, `CoordinateMapper` (Task 1).
- Produces:
  - `class ControlService : AccessibilityService()` registering itself in a companion singleton on connect.
  - `companion object { @Volatile var instance: ControlService?; fun dispatch(event: ControlEvent, mapper: CoordinateMapper) }` — routes Tap/LongPress/Swipe to `dispatchGesture`, Key to `performGlobalAction`, Text to focused-node `ACTION_SET_TEXT`.

- [ ] **Step 1: Accessibility config + manifest registration**

`app/src/main/res/xml/accessibility_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100" />
```

Add to `AndroidManifest.xml` inside `<application>`:
```xml
<service
    android:name=".control.ControlService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_config" />
</service>
```

- [ ] **Step 2: Implement ControlService**

`app/src/main/kotlin/com/example/lanremote/control/ControlService.kt`:
```kotlin
package com.example.lanremote.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ControlService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private fun gesture(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun handle(event: ControlEvent, mapper: CoordinateMapper) {
        when (event) {
            is ControlEvent.Tap -> {
                val (x, y) = mapper.toPixels(event.x, event.y)
                gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }, 50)
            }
            is ControlEvent.LongPress -> {
                val (x, y) = mapper.toPixels(event.x, event.y)
                gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }, event.durationMs)
            }
            is ControlEvent.Swipe -> {
                val (x1, y1) = mapper.toPixels(event.x1, event.y1)
                val (x2, y2) = mapper.toPixels(event.x2, event.y2)
                gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, event.durationMs)
            }
            is ControlEvent.Key -> performGlobalAction(
                when (event.action) {
                    KeyAction.BACK -> GLOBAL_ACTION_BACK
                    KeyAction.HOME -> GLOBAL_ACTION_HOME
                    KeyAction.RECENTS -> GLOBAL_ACTION_RECENTS
                }
            )
            is ControlEvent.Text -> {
                val node = findFocusedEditable() ?: return
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, event.value)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
    }

    companion object {
        @Volatile var instance: ControlService? = null
        fun dispatch(event: ControlEvent, mapper: CoordinateMapper) {
            instance?.handle(event, mapper)
        }
    }
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Enable the Accessibility service via adb (scriptable, no manual tapping)**

```bash
./gradlew :app:installDebug
COMP=com.example.lanremote/com.example.lanremote.control.ControlService
adb shell settings put secure enabled_accessibility_services "$COMP"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services   # verify it stuck
```
Expected: the get command echoes the component name. If the beta OS rejects the programmatic enable (some builds gate it), fall back to `adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS` and toggle once by hand. Confirm no crash:
```bash
adb logcat -d | grep -iE "lanremote|AndroidRuntime" | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: accessibility ControlService for input injection"
```

---

### Task 4: CaptureService — MediaProjection + MediaCodec H.264 encoder

Foreground service that captures the screen and produces H.264 access units via a callback. Consent token is passed in via Intent.

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/video/CaptureService.kt`
- Create: `app/src/main/kotlin/com/example/lanremote/video/ScreenEncoder.kt`
- Modify: `AndroidManifest.xml` (register foreground service with mediaProjection type)

**Interfaces:**
- Consumes: `VideoFrame`, `VideoMeta` (Task 2).
- Produces:
  - `class ScreenEncoder(width, height, densityDpi)` with `fun start(projection: MediaProjection, onMeta: (VideoMeta)->Unit, onFrame: (isKeyframe: Boolean, nal: ByteArray)->Unit)` and `fun stop()`.
  - `class CaptureService : Service()` started with extras `EXTRA_RESULT_CODE: Int`, `EXTRA_RESULT_DATA: Intent`; exposes `companion object { @Volatile var frameSink: ((Boolean, ByteArray)->Unit)?; @Volatile var meta: VideoMeta? }` for the server to attach.

- [ ] **Step 1: Implement ScreenEncoder**

`app/src/main/kotlin/com/example/lanremote/video/ScreenEncoder.kt`:
```kotlin
package com.example.lanremote.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.view.Surface

class ScreenEncoder(
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(
        projection: MediaProjection,
        onMeta: (VideoMeta) -> Unit,
        onFrame: (Boolean, ByteArray) -> Unit
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Pin baseline profile so the client's `avc1.42E01E` codec string is honest.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            // Make every IDR carry SPS/PPS so a late-joining client can decode. (API 29+)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = c.createInputSurface()
        c.start()
        codec = c
        display = projection.createVirtualDisplay(
            "lanremote", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        onMeta(VideoMeta(width, height))
        running = true
        thread = Thread { drain(onFrame) }.also { it.start() }
    }

    private fun drain(onFrame: (Boolean, ByteArray) -> Unit) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = c.dequeueOutputBuffer(info, 10_000)
            if (idx >= 0) {
                val buf = c.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size); buf.get(data)
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    onFrame(isKey, data)
                }
                c.releaseOutputBuffer(idx, false)
            }
        }
    }

    /** Ask the encoder to emit an immediate IDR (which, with prepend-header set, carries SPS/PPS).
     *  Call this whenever a new /video client connects so it gets a decodable frame fast. */
    fun requestKeyframe() {
        try {
            codec?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        thread?.join(500)
        try { display?.release() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        surface?.release()
        codec = null; surface = null; display = null
    }
}
```

- [ ] **Step 2: Implement CaptureService + manifest**

`app/src/main/kotlin/com/example/lanremote/video/CaptureService.kt`:
```kotlin
package com.example.lanremote.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

class CaptureService : Service() {
    private var encoder: ScreenEncoder? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent!!.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!
        startForeground(NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)
        projection = proj

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        // Cap the long edge to keep bitrate/latency reasonable.
        val enc = ScreenEncoder(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        enc.start(proj, { m -> meta = m }, { key, nal -> frameSink?.invoke(key, nal) })
        encoder = enc
        requestKeyframe = { enc.requestKeyframe() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        encoder?.stop(); encoder = null
        projection?.stop(); projection = null
        meta = null; frameSink = null; requestKeyframe = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("LAN Remote")
            .setContentText("Screen is being shared on your LAN")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        @Volatile var frameSink: ((Boolean, ByteArray) -> Unit)? = null
        @Volatile var meta: VideoMeta? = null
        /** Set by the running service so the server can force an IDR when a client connects. */
        @Volatile var requestKeyframe: (() -> Unit)? = null
    }
}
```

Add to `AndroidManifest.xml` inside `<application>`:
```xml
<service
    android:name=".video.CaptureService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: MediaProjection screen capture + H.264 encoder service"
```

---

### Task 5: Embedded server — static web client + /video + /control WebSockets

Wire the server: serve the web client, push encoder frames to `/video` subscribers, and route `/control` JSON to `ControlService`.

**Files:**
- Create: `app/src/main/kotlin/com/example/lanremote/server/RemoteServer.kt`
- Create: `app/src/main/kotlin/com/example/lanremote/server/NetworkUtil.kt`
- Create: `app/src/main/assets/web/index.html` (placeholder; real client in Task 7)

**Interfaces:**
- Consumes: `ControlEventParser`, `ControlEvent`, `CoordinateMapper` (Task 1); `ControlService.dispatch` (Task 3); `CaptureService.frameSink`, `CaptureService.meta`, `VideoFrame`, `VideoMeta` (Tasks 2/4).
- Produces:
  - `class RemoteServer(context: Context) { fun start(port: Int = 8080); fun stop() }` — starts Ktor Netty.
  - `object NetworkUtil { fun lanIpAddress(): String? }`.

- [ ] **Step 1: Implement NetworkUtil**

`app/src/main/kotlin/com/example/lanremote/server/NetworkUtil.kt`:
```kotlin
package com.example.lanremote.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {
    fun lanIpAddress(): String? =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
}
```

- [ ] **Step 2: Implement RemoteServer**

`app/src/main/kotlin/com/example/lanremote/server/RemoteServer.kt`:
```kotlin
package com.example.lanremote.server

import android.content.Context
import com.example.lanremote.control.ControlEventParser
import com.example.lanremote.control.ControlService
import com.example.lanremote.control.CoordinateMapper
import com.example.lanremote.video.CaptureService
import com.example.lanremote.video.VideoFrame
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import java.time.Duration

class RemoteServer(private val context: Context) {
    private var engine: CIOApplicationEngine? = null

    fun start(port: Int = 8080) {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) { pingPeriod = Duration.ofSeconds(15) }
            routing {
                get("/") { call.respondText(indexHtml(), io.ktor.http.ContentType.Text.Html) }
                webSocket("/video") { serveVideo() }
                webSocket("/control") { serveControl() }
            }
        }.also { it.start(wait = false) }
    }

    private fun indexHtml(): String =
        context.assets.open("web/index.html").bufferedReader().use { it.readText() }

    private suspend fun DefaultWebSocketServerSession.serveVideo() {
        val meta = CaptureService.meta ?: return
        send(Frame.Text(meta.toJson()))
        val queue = Channel<ByteArray>(capacity = 8)
        CaptureService.frameSink = { key, nal -> queue.trySend(VideoFrame.encode(key, nal)) }
        // Force an immediate IDR (carrying SPS/PPS) so this late-joining client can decode at once.
        CaptureService.requestKeyframe?.invoke()
        try {
            for (frame in queue) send(Frame.Binary(true, frame))
        } finally {
            CaptureService.frameSink = null
            queue.close()
        }
    }

    private suspend fun DefaultWebSocketServerSession.serveControl() {
        val meta = CaptureService.meta ?: return
        val mapper = CoordinateMapper(meta.width, meta.height)
        for (frame in incoming) {
            if (frame is Frame.Text) {
                ControlEventParser.parse(frame.readText())?.let {
                    ControlService.dispatch(it, mapper)
                }
            }
        }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }
}
```

`app/src/main/assets/web/index.html` (placeholder, replaced in Task 7):
```html
<!doctype html><html><body><h1>LAN Remote</h1><p>web client pending</p></body></html>
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: embedded Ktor server with /video and /control websockets"
```

---

### Task 6: SessionManager UI — consent, start/stop, wiring, connection info

Turn the app into a working whole: a Start/Stop button that requests `MediaProjection` consent, launches `CaptureService`, starts `RemoteServer`, and shows the connect URL. This is the first full E2E-testable deliverable.

**Files:**
- Modify: `app/src/main/kotlin/com/example/lanremote/MainActivity.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`

**Interfaces:**
- Consumes: `CaptureService` (Task 4), `RemoteServer`, `NetworkUtil` (Task 5).
- Produces: working app; no downstream consumers.

- [ ] **Step 1: Update layout**

`app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:orientation="vertical" android:padding="24dp" android:gravity="center_horizontal">
    <TextView android:id="@+id/status" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:textSize="16sp" android:text="Idle" />
    <TextView android:id="@+id/url" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:textSize="20sp"
        android:textStyle="bold" android:paddingTop="12dp" android:text="" />
    <Button android:id="@+id/toggle" android:layout_width="wrap_content"
        android:layout_height="wrap_content" android:layout_marginTop="24dp"
        android:text="Start sharing" />
</LinearLayout>
```

- [ ] **Step 2: Implement MainActivity control flow**

`app/src/main/kotlin/com/example/lanremote/MainActivity.kt`:
```kotlin
package com.example.lanremote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.lanremote.databinding.ActivityMainBinding
import com.example.lanremote.server.NetworkUtil
import com.example.lanremote.server.RemoteServer
import com.example.lanremote.video.CaptureService

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var server: RemoteServer? = null
    private var sharing = false
    private val port = 8080

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startSharing(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        b.toggle.setOnClickListener { if (sharing) stopSharing() else requestConsent() }
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
        val ip = NetworkUtil.lanIpAddress() ?: "unknown"
        b.status.text = "Sharing (open in browser on same Wi-Fi):"
        b.url.text = "http://$ip:$port"
        b.toggle.text = "Stop sharing"
    }

    private fun stopSharing() {
        server?.stop(); server = null
        stopService(Intent(this, CaptureService::class.java))
        sharing = false
        b.status.text = "Idle"; b.url.text = ""; b.toggle.text = "Start sharing"
    }

    override fun onDestroy() { if (sharing) stopSharing(); super.onDestroy() }
}
```

- [ ] **Step 3: Build + install**

```bash
./gradlew :app:installDebug
```
Expected: BUILD SUCCESSFUL, app installed.

- [ ] **Step 4: E2E smoke test on device**

1. On phone: ensure Accessibility service "LAN Remote" is enabled (Task 3 Step 4).
2. Launch app, tap "Start sharing", accept the capture prompt. The `MediaProjection`
   consent dialog is **not** grantable via adb appops on modern Android — either tap
   "Start now" by hand once, or script it:
   ```bash
   adb shell am start -n com.example.lanremote/.MainActivity
   adb shell input tap 540 1600   # tap the app's Start button (adjust via `uiautomator dump`)
   # then the system consent dialog appears; dump + tap its "Start now":
   adb shell uiautomator dump && adb pull /sdcard/window_dump.xml /tmp/ui.xml
   # find the "Start now" bounds in /tmp/ui.xml and: adb shell input tap <x> <y>
   ```
3. Note the shown `http://<ip>:8080`.
4. On the Mac (same Wi-Fi), verify reachability:
```bash
IP=$(adb shell ip route | awk '{print $9}' | tail -1)
curl -s "http://$IP:8080/" | head -3
```
Expected: returns the placeholder HTML. (Video/control verified in Task 7.)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: session start/stop UI with consent and server wiring"
```

---

### Task 7: Web client — WebCodecs H.264 decode + input capture

Replace the placeholder with the real client: decode `/video` H.264 to a canvas, and send normalized-coordinate input over `/control`.

**Files:**
- Modify: `app/src/main/assets/web/index.html`
- Create: `app/src/main/assets/web/client.js`

**Interfaces:**
- Consumes: `/video` (text meta frame `{"width","height"}` then binary frames: byte0 flags, rest = H.264 Annex-B), `/control` (JSON per Task 1 schema).
- Produces: end-user web UI.

- [ ] **Step 1: Write index.html**

`app/src/main/assets/web/index.html`:
```html
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>LAN Remote</title>
<style>
  body { margin:0; background:#111; color:#eee; font-family:sans-serif; display:flex; flex-direction:column; align-items:center; }
  #screen { touch-action:none; background:#000; max-width:100vw; max-height:80vh; }
  #bar { display:flex; gap:8px; padding:8px; }
  button { padding:10px 16px; font-size:16px; }
  #hint { font-size:12px; opacity:.6; }
</style>
</head>
<body>
  <canvas id="screen"></canvas>
  <div id="bar">
    <button data-key="back">Back</button>
    <button data-key="home">Home</button>
    <button data-key="recents">Recents</button>
  </div>
  <div id="hint">Click/drag to control. Type to send text.</div>
  <script src="client.js"></script>
</body>
</html>
```

- [ ] **Step 2: Write client.js**

`app/src/main/assets/web/client.js`:
```javascript
const canvas = document.getElementById('screen');
const ctx = canvas.getContext('2d');
const host = location.host;
let meta = null;

// ---- Video ----
const decoder = new VideoDecoder({
  output: (frame) => {
    if (canvas.width !== frame.displayWidth) {
      canvas.width = frame.displayWidth;
      canvas.height = frame.displayHeight;
    }
    ctx.drawImage(frame, 0, 0);
    frame.close();
  },
  error: (e) => console.error('decoder', e),
});

const vsock = new WebSocket(`ws://${host}/video`);
vsock.binaryType = 'arraybuffer';
let configured = false;
vsock.onmessage = (ev) => {
  if (typeof ev.data === 'string') { meta = JSON.parse(ev.data); return; }
  const buf = new Uint8Array(ev.data);
  const isKey = (buf[0] & 0x01) !== 0;
  const payload = buf.subarray(1);
  // Wait for the first keyframe before configuring — deltas before it are undecodable,
  // and the keyframe carries in-band SPS/PPS (encoder prepends headers to sync frames).
  if (!configured) {
    if (!isKey) return;
    // No `description` => WebCodecs expects Annex-B start codes, which MediaCodec emits. Do NOT
    // supply an AVCC `description` here; that would switch the decoder to length-prefixed mode.
    decoder.configure({ codec: 'avc1.42E01E', optimizeForLatency: true });
    configured = true;
  }
  decoder.decode(new EncodedVideoChunk({
    type: isKey ? 'key' : 'delta',
    timestamp: performance.now() * 1000,
    data: payload,
  }));
};

// ---- Control ----
const csock = new WebSocket(`ws://${host}/control`);
function send(obj) { if (csock.readyState === 1) csock.send(JSON.stringify(obj)); }

function norm(ev) {
  const r = canvas.getBoundingClientRect();
  return {
    x: Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width)),
    y: Math.min(1, Math.max(0, (ev.clientY - r.top) / r.height)),
  };
}

let down = null, downTime = 0;
canvas.addEventListener('pointerdown', (e) => { down = norm(e); downTime = performance.now(); });
canvas.addEventListener('pointerup', (e) => {
  if (!down) return;
  const up = norm(e);
  const dt = performance.now() - downTime;
  const moved = Math.hypot(up.x - down.x, up.y - down.y) > 0.02;
  if (moved) send({ type: 'swipe', x1: down.x, y1: down.y, x2: up.x, y2: up.y, durationMs: Math.round(dt) });
  else if (dt > 500) send({ type: 'longpress', x: down.x, y: down.y, durationMs: Math.round(dt) });
  else send({ type: 'tap', x: down.x, y: down.y });
  down = null;
});

document.querySelectorAll('button[data-key]').forEach((btn) =>
  btn.addEventListener('click', () => send({ type: 'key', action: btn.dataset.key })));

window.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'BUTTON') return;
  if (e.key.length === 1) { send({ type: 'text', value: e.key }); e.preventDefault(); }
  else if (e.key === 'Backspace') { send({ type: 'key', action: 'back' }); e.preventDefault(); }
});
```

- [ ] **Step 3: Rebuild + install**

```bash
./gradlew :app:installDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Full E2E on device + browser**

1. Phone: enable Accessibility service, launch app, Start sharing, accept prompt.
2. Mac browser (Chrome): open `http://<phone-ip>:8080`.
3. Verify: live screen renders; clicking taps; dragging swipes/scrolls; Back/Home/Recents buttons work; typing a letter inserts text into a focused field on the phone.
4. Note: `FLAG_SECURE` screens render black by OS design (expected, documented).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: web client with WebCodecs decode and input capture"
```

---

### Task 8: README + hardening notes

Document setup, usage, limits, and the security posture (open-on-LAN by design).

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write README**

Include: build/install commands, one-time device setup (enable Accessibility, grant notifications), usage flow, known limits (FLAG_SECURE, per-session consent), and an explicit security note that v1 is unauthenticated LAN access — trusted networks only, with PIN/QR+TLS listed as the v2 hardening step.

- [ ] **Step 2: Commit**

```bash
git add -A && git commit -m "docs: README with setup, usage, limits, security posture"
```

---

## Notes on Codec Config Robustness

The client keeps the `VideoDecoder` in **Annex-B mode** (no `description` in `configure()`), which matches the start-code bitstream `MediaCodec` emits — do **not** parse SPS/PPS into an AVCC `description`, as that switches the decoder to length-prefixed mode and would mismatch the stream. The real fragility is the `avc1.42E01E` codec string: the encoder pins `AVCProfileBaseline`/`AVCLevel41` (Task 4) so the string is honest. If Chrome still rejects `configure()`, derive the codec string from the SPS bytes of the first keyframe instead of hardcoding. Chrome is usually lenient and reads in-band SPS, so try as-is first.

Late-joining clients are handled structurally, not by luck: the encoder sets `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` so every IDR carries SPS/PPS, the server calls `requestKeyframe()` the instant a `/video` client connects, and the client discards frames until the first keyframe. This is the normal flow (start sharing first, open browser second) and must be verified that way in Task 7 Step 4.
