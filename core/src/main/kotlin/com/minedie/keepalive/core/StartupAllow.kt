package com.minedie.keepalive.core

/**
 * Decides whether ColorOS may be told to allow one startService call.
 * Only a service the user selected is allowed. Everything else keeps the system decision.
 */
object StartupAllow {
    fun matches(master: Boolean, apps: List<GuardedApp>, packageName: String, className: String): Boolean {
        if (!master || packageName.isBlank() || className.isBlank()) return false
        val app = apps.firstOrNull { it.enabled && it.packageName == packageName } ?: return false
        return app.targets().any { componentClass(it, packageName) == className }
    }
}

fun componentClass(flat: String, packageName: String): String? {
    val slash = flat.indexOf('/')
    if (slash <= 0 || slash >= flat.lastIndex) return null
    if (flat.substring(0, slash) != packageName) return null
    val raw = flat.substring(slash + 1)
    if (raw.isBlank()) return null
    return if (raw.startsWith(".")) packageName + raw else raw
}

fun canonicalComponent(packageName: String, flat: String): String? {
    val cls = componentClass(flat, packageName) ?: return null
    return "$packageName/$cls"
}

fun serviceRunning(running: Set<String>, packageName: String, flat: String): Boolean {
    val cls = componentClass(flat, packageName) ?: return false
    return "$packageName/$cls" in running
}
