package com.example.lanremote.control

/**
 * Decides whether to show the post-update "turn control back on" dialog.
 * Pure logic so it can be unit tested; [MainActivity] supplies the stored values.
 */
object ReenablePrompt {
    const val NO_VERSION = -1

    /**
     * Show once per update: this was an upgrade ([lastVersion] < [currentVersion], not a fresh
     * install), the service is still off, and we haven't shown for [currentVersion] yet.
     */
    fun shouldShow(lastVersion: Int, currentVersion: Int, serviceOn: Boolean, shownFor: Int): Boolean =
        lastVersion != NO_VERSION && lastVersion < currentVersion && !serviceOn && shownFor != currentVersion
}
