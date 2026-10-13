package com.example.lanremote.control

/**
 * Checks whether our accessibility service is enabled.
 *
 * Android stores the enabled-services setting with the *short* class form
 * ("com.example.lanremote/.control.ControlService"), so a plain string compare
 * against the fully-qualified name never matches. Both forms are accepted here.
 * Pure Kotlin so it can be unit tested.
 */
object ControlState {
    fun isEnabled(setting: String?, packageName: String, className: String): Boolean {
        if (setting.isNullOrEmpty()) return false
        val shortClass = className
            .takeIf { it.startsWith(packageName) }
            ?.removePrefix(packageName) // e.g. ".control.ControlService"
        return setting.split(':').any { entry ->
            entry.equals("$packageName/$className", ignoreCase = true) ||
                (shortClass != null && entry.equals("$packageName/$shortClass", ignoreCase = true))
        }
    }
}
