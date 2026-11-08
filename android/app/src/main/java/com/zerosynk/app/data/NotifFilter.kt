package com.zerosynk.app.data

/** Shared notification filter for phone → PC and Windows → phone mirroring. */
object NotifFilter {
    fun allows(prefs: Prefs, packageName: String?, appName: String?, important: Boolean): Boolean {
        val mode = prefs.notifMode
        if (mode == "important" && !important) return false

        val configured = prefs.notifApps
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (configured.isEmpty()) return mode != "allow"

        val candidates = listOfNotNull(packageName, appName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val matched = configured.any { entry ->
            candidates.any { it.equals(entry, ignoreCase = true) }
        }
        return when (mode) {
            "allow" -> matched
            "block" -> !matched
            else -> true
        }
    }
}
