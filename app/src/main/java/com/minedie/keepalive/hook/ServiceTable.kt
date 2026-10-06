package com.minedie.keepalive.hook

import android.content.ComponentName
import de.robv.android.xposed.XposedHelpers

internal data class RunningService(
    val packageName: String,
    val className: String,
    val processName: String,
)

/**
 * Reads services that currently have a process. The map lives on ActivityManagerService,
 * so this only works inside system_server.
 */
internal data class ServiceSnapshot(
    val services: List<RunningService>,
    /** False when the map was missing or empty, so a failed read is not "every service is dead". */
    val trusted: Boolean,
)

internal object ServiceTable {
    fun read(ams: Any): ServiceSnapshot {
        return try {
            synchronized(ams) {
                val active = XposedHelpers.getObjectField(ams, "mServices") ?: return ServiceSnapshot(emptyList(), false)
                val users = XposedHelpers.getObjectField(active, "mServiceMap") ?: return ServiceSnapshot(emptyList(), false)
                val found = LinkedHashMap<String, RunningService>()
                val count = XposedHelpers.callMethod(users, "size") as? Int ?: return ServiceSnapshot(emptyList(), false)
                for (index in 0 until count) {
                    val userMap = XposedHelpers.callMethod(users, "valueAt", index) ?: continue
                    val table = XposedHelpers.getObjectField(userMap, "mServicesByInstanceName") as? Map<*, *>
                        ?: continue
                    for (record in table.values) {
                        val row = rowOf(record ?: continue) ?: continue
                        found.putIfAbsent("${row.packageName}/${row.className}/${row.processName}", row)
                    }
                }
                val rows = found.values.toList()
                ServiceSnapshot(rows, rows.isNotEmpty())
            }
        } catch (error: Throwable) {
            Watchdog.noteHookError("读取服务表失败: ${error.javaClass.simpleName}")
            ServiceSnapshot(emptyList(), false)
        }
    }

    private fun rowOf(record: Any): RunningService? {
        val running = try {
            XposedHelpers.getObjectField(record, "app")
        } catch (_: Throwable) {
            null
        }
        if (running == null) return null
        val component = componentOf(record) ?: return null
        if (component.packageName.isBlank() || component.className.isBlank()) return null
        val process = try {
            XposedHelpers.getObjectField(record, "processName") as? String
        } catch (_: Throwable) {
            null
        }.orEmpty().ifBlank { component.packageName }
        return RunningService(component.packageName, component.className, process)
    }

    private fun componentOf(record: Any): ComponentName? {
        for (field in listOf("instanceName", "name")) {
            val value = try {
                XposedHelpers.getObjectField(record, field)
            } catch (_: Throwable) {
                null
            }
            if (value is ComponentName) return value
        }
        return null
    }
}
