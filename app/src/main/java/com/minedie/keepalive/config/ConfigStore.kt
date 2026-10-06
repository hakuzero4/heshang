package com.minedie.keepalive.config

import android.content.Context
import android.util.Log
import com.minedie.keepalive.PREFS_NAME
import com.minedie.keepalive.core.GuardedApp
import com.minedie.keepalive.core.Ranges
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class StoredConfig(
    val master: Boolean,
    val intervalSec: Int,
    val bootDelaySec: Int,
    val retention: Int,
    val token: String,
    val apps: List<GuardedApp>,
    val a11y: Set<String>,
    val showSystem: Boolean,
)

/**
 * ColorOS on this Ace 6T does not create the shared_prefs directory for this app,
 * so the watchdog config lives in the app files directory instead.
 */
internal class ConfigStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "$PREFS_NAME.json")

    fun ensureToken(): String = load().token

    @Synchronized
    fun load(): StoredConfig {
        val json = read()
        if (json.optString(KEY_TOKEN).isBlank()) {
            json.put(KEY_TOKEN, UUID.randomUUID().toString())
            write(json)
        }
        return decode(json)
    }

    fun setMaster(enabled: Boolean) = update { it.put(KEY_MASTER, enabled) }

    fun setInterval(seconds: Int) = update { it.put(KEY_INTERVAL, Ranges.intervalSec(seconds)) }

    fun setBootDelay(seconds: Int) = update { it.put(KEY_BOOT, Ranges.bootDelaySec(seconds)) }

    fun setRetention(count: Int) = update { it.put(KEY_RETENTION, Ranges.retention(count)) }

    fun setShowSystem(show: Boolean) = update { it.put(KEY_SYSTEM, show) }

    fun setApps(apps: List<GuardedApp>) = update { it.put(KEY_APPS, ConfigCodec.appsToJson(apps)) }

    fun setA11y(components: Set<String>) = update { it.put(KEY_A11Y, ConfigCodec.stringsToJson(components)) }

    @Synchronized
    private fun update(block: (JSONObject) -> Unit) {
        val json = read()
        if (json.optString(KEY_TOKEN).isBlank()) json.put(KEY_TOKEN, UUID.randomUUID().toString())
        block(json)
        write(json)
    }

    private fun read(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (error: Throwable) {
            Log.e("KeepAlive", "config read failed: ${error.javaClass.simpleName}")
            JSONObject()
        }
    }

    private fun write(json: JSONObject) {
        val directory = file.parentFile ?: return
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e("KeepAlive", "config directory missing")
            return
        }
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeText(json.toString())
        if (!temporary.renameTo(file)) {
            file.writeText(json.toString())
            temporary.delete()
        }
    }

    private fun decode(json: JSONObject): StoredConfig {
        return StoredConfig(
            master = json.optBoolean(KEY_MASTER, false),
            intervalSec = Ranges.intervalSec(json.optInt(KEY_INTERVAL, 20)),
            bootDelaySec = Ranges.bootDelaySec(json.optInt(KEY_BOOT, 20)),
            retention = Ranges.retention(json.optInt(KEY_RETENTION, 300)),
            token = json.optString(KEY_TOKEN),
            apps = ConfigCodec.appsFromJson(json.optString(KEY_APPS, "[]")),
            a11y = ConfigCodec.stringsFromJson(json.optString(KEY_A11Y, "[]")),
            showSystem = json.optBoolean(KEY_SYSTEM, false),
        )
    }

    private companion object {
        const val KEY_MASTER = "master"
        const val KEY_INTERVAL = "interval_sec"
        const val KEY_BOOT = "boot_delay_sec"
        const val KEY_RETENTION = "log_retention"
        const val KEY_TOKEN = "token"
        const val KEY_APPS = "apps"
        const val KEY_A11Y = "a11y"
        const val KEY_SYSTEM = "show_system"
    }
}
