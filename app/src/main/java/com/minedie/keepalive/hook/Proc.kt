package com.minedie.keepalive.hook

import android.os.Build
import de.robv.android.xposed.XposedHelpers

internal data class ProcessRow(
    val processName: String,
    val pid: Int,
    val record: Any,
)

internal object Adj {
    const val PERCEPTIBLE = 200

    fun setMax(record: Any, maxAdj: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val state = fieldOrNull(record, "mState")
            if (state != null) {
                XposedHelpers.callMethod(state, "setMaxAdj", maxAdj)
                return
            }
            try {
                XposedHelpers.callMethod(record, "setMaxAdj", maxAdj)
            } catch (_: Throwable) {
                XposedHelpers.setIntField(record, "mMaxAdj", maxAdj)
            }
        } else {
            XposedHelpers.setIntField(record, "maxAdj", maxAdj)
        }
    }

    fun readDefault(classLoader: ClassLoader): Int {
        return try {
            val clazz = XposedHelpers.findClass("com.android.server.am.ProcessList", classLoader)
            XposedHelpers.getStaticIntField(clazz, "UNKNOWN_ADJ")
        } catch (_: Throwable) {
            1001
        }
    }

    private fun fieldOrNull(target: Any, name: String): Any? {
        return try {
            XposedHelpers.getObjectField(target, name)
        } catch (_: Throwable) {
            null
        }
    }
}

internal object ProcessTable {
    fun read(ams: Any): List<ProcessRow> {
        val records = try {
            synchronized(ams) {
                val processList = XposedHelpers.getObjectField(ams, "mProcessList") ?: return emptyList()
                val lru = XposedHelpers.getObjectField(processList, "mLruProcesses") as? List<*> ?: return emptyList()
                lru.mapNotNull { it }
            }
        } catch (error: Throwable) {
            Watchdog.noteHookError("读取进程表失败: ${error.javaClass.simpleName}")
            return emptyList()
        }
        return records.mapNotNull { record ->
            val name = try {
                XposedHelpers.getObjectField(record, "processName") as? String
            } catch (_: Throwable) {
                null
            } ?: return@mapNotNull null
            ProcessRow(name, pidOf(record), record)
        }
    }

    private fun pidOf(record: Any): Int {
        try {
            val value = XposedHelpers.callMethod(record, "getPid")
            if (value is Int && value > 0) return value
        } catch (_: Throwable) {
        }
        for (field in listOf("pid", "mPid")) {
            try {
                val value = XposedHelpers.getIntField(record, field)
                if (value > 0) return value
            } catch (_: Throwable) {
            }
        }
        return 0
    }
}
