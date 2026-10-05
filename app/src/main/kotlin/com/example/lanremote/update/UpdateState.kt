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
    /** [detail] is the system installer's message, when there is one. */
    data class Failed(
        override val info: ReleaseInfo,
        val reason: FailReason,
        val detail: String? = null,
    ) : UpdateState()
}

/** Why an update failed; the UI maps each to a localized message. */
enum class FailReason { DOWNLOAD, VERIFY, INSTALL }
