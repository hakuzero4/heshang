package com.minedie.keepalive.config

import com.minedie.keepalive.core.EventDraft
import com.minedie.keepalive.core.EventType
import com.minedie.keepalive.core.GuardedApp
import com.minedie.keepalive.core.Ranges
import org.json.JSONArray
import org.json.JSONObject

internal object ConfigCodec {
    fun appsToJson(apps: List<GuardedApp>): String {
        val array = JSONArray()
        apps.forEach { app ->
            val targets = app.targets()
            array.put(
                JSONObject()
                    .put("pkg", app.packageName)
                    .put("label", app.label)
                    .put("component", targets.firstOrNull().orEmpty())
                    .put("components", JSONArray(targets))
                    .put("enabled", app.enabled),
            )
        }
        return array.toString()
    }

    fun appsFromJson(raw: String?): List<GuardedApp> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val pkg = item.optString("pkg")
                    if (pkg.isBlank()) continue
                    val listed = buildList {
                        val array = item.optJSONArray("components")
                        if (array != null) {
                            for (cursor in 0 until array.length()) {
                                val value = array.optString(cursor).trim()
                                if (value.isNotBlank()) add(value)
                            }
                        }
                    }.distinct()
                    val single = item.optString("component").trim()
                    val targets = listed.ifEmpty { listOf(single).filter { it.isNotBlank() } }
                    add(
                        GuardedApp(
                            packageName = pkg,
                            label = item.optString("label").ifBlank { pkg },
                            component = targets.firstOrNull().orEmpty(),
                            enabled = item.optBoolean("enabled", true),
                            components = targets,
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun stringsToJson(values: Collection<String>): String {
        val array = JSONArray()
        values.filter { it.isNotBlank() }.distinct().sorted().forEach { array.put(it) }
        return array.toString()
    }

    fun stringsFromJson(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun servicesToJson(services: List<Triple<String, String, String>>): String {
        val array = JSONArray()
        services.forEach { (pkg, cls, proc) ->
            if (pkg.isBlank() || cls.isBlank()) return@forEach
            array.put(JSONObject().put("pkg", pkg).put("cls", cls).put("proc", proc))
        }
        return array.toString()
    }

    fun servicesFromJson(raw: String?): List<Triple<String, String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val pkg = item.optString("pkg")
                    val cls = item.optString("cls")
                    if (pkg.isBlank() || cls.isBlank()) continue
                    add(Triple(pkg, cls, item.optString("proc").ifBlank { pkg }))
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun eventsToJson(events: List<EventDraft>): String {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("type", event.type.name)
                    .put("title", event.title)
                    .put("detail", event.detail)
                    .put("pkg", event.packageName ?: ""),
            )
        }
        return array.toString()
    }

    fun eventsFromJson(raw: String?): List<EventDraft> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = runCatching { EventType.valueOf(item.optString("type")) }.getOrNull() ?: continue
                    add(
                        EventDraft(
                            type = type,
                            title = item.optString("title"),
                            detail = item.optString("detail"),
                            packageName = item.optString("pkg").ifBlank { null },
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun configToJson(config: StoredConfig): String {
        return configToJson(
            LoadedConfig(
                master = config.master,
                intervalSec = config.intervalSec,
                bootDelaySec = config.bootDelaySec,
                retention = config.retention,
                token = config.token,
                apps = config.apps,
                a11y = config.a11y,
            ),
        )
    }

    fun configToJson(config: LoadedConfig): String {
        return JSONObject()
            .put(KEY_MASTER, config.master)
            .put(KEY_INTERVAL, config.intervalSec)
            .put(KEY_BOOT, config.bootDelaySec)
            .put(KEY_RETENTION, config.retention)
            .put(KEY_TOKEN, config.token)
            .put(KEY_APPS, appsToJson(config.apps))
            .put(KEY_A11Y, stringsToJson(config.a11y))
            .toString()
    }

    fun configFromJson(raw: String?): LoadedConfig? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val token = json.optString(KEY_TOKEN)
            if (token.isBlank()) return null
            LoadedConfig(
                master = json.optBoolean(KEY_MASTER, false),
                intervalSec = Ranges.intervalSec(firstInt(json, KEY_INTERVAL, "interval_sec", 20)),
                bootDelaySec = Ranges.bootDelaySec(firstInt(json, KEY_BOOT, "boot_delay_sec", 20)),
                retention = Ranges.retention(firstInt(json, KEY_RETENTION, "log_retention", 300)),
                token = token,
                apps = appsFromJson(jsonField(json, KEY_APPS)),
                a11y = stringsFromJson(jsonField(json, KEY_A11Y)),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun firstInt(json: JSONObject, primary: String, legacy: String, fallback: Int): Int {
        if (json.has(primary)) return json.optInt(primary, fallback)
        if (json.has(legacy)) return json.optInt(legacy, fallback)
        return fallback
    }

    /** The store writes `apps` and `a11y` as strings. A raw array is accepted too. */
    private fun jsonField(json: JSONObject, key: String): String {
        val value = json.opt(key)
        return when (value) {
            is String -> value
            null, JSONObject.NULL -> ""
            else -> value.toString()
        }
    }

    private const val KEY_MASTER = "master"
    private const val KEY_INTERVAL = "interval"
    private const val KEY_BOOT = "bootDelay"
    private const val KEY_RETENTION = "retention"
    private const val KEY_TOKEN = "token"
    private const val KEY_APPS = "apps"
    private const val KEY_A11Y = "a11y"
}

internal data class LoadedConfig(
    val master: Boolean,
    val intervalSec: Int,
    val bootDelaySec: Int,
    val retention: Int,
    val token: String,
    val apps: List<GuardedApp>,
    val a11y: Set<String>,
)
