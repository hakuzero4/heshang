package com.minedie.keepalive.core

/**
 * Decides whether ColorOS may be told to allow one startService call.
 * Only the exact service the user enabled is allowed. Everything else keeps the system decision.
 */
object StartupAllow {
    fun matches(master: Boolean, apps: List<GuardedApp>, packageName: String, className: String): Boolean {
        if (!master || packageName.isBlank() || className.isBlank()) return false
        val app = apps.firstOrNull { it.enabled && it.packageName == packageName } ?: return false
        return className == classOf(app.component, packageName)
    }

    private fun classOf(flat: String, packageName: String): String? {
        val slash = flat.indexOf('/')
        if (slash <= 0 || slash >= flat.lastIndex) return null
        if (flat.substring(0, slash) != packageName) return null
        val raw = flat.substring(slash + 1)
        if (raw.isBlank()) return null
        return if (raw.startsWith(".")) packageName + raw else raw
    }
}
