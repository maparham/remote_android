package com.example.lanremote.update

/** A GitHub release that ships an APK. [sha256] is lower-case hex, or null if GitHub gave no digest. */
data class ReleaseInfo(
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String?,
)
