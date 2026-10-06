package com.minedie.keepalive.config

import com.minedie.keepalive.core.EventDraft
import com.minedie.keepalive.core.EventType
import com.minedie.keepalive.core.GuardedApp
import org.json.JSONArray
import org.json.JSONObject

internal object ConfigCodec {
    fun appsToJson(apps: List<GuardedApp>): String {
        val array = JSONArray()
        apps.forEach { app ->
            array.put(
                JSONObject()
                    .put("pkg", app.packageName)
                    .put("label", app.label)
                    .put("component", app.component)
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
                    add(
                        GuardedApp(
                            packageName = pkg,
                            label = item.optString("label").ifBlank { pkg },
                            component = item.optString("component"),
                            enabled = item.optBoolean("enabled", true),
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
}
