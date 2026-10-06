package com.minedie.keepalive.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.minedie.keepalive.core.Ranges
import com.minedie.keepalive.data.Report
import com.minedie.keepalive.data.ReportWriter
import kotlinx.coroutines.runBlocking

/**
 * system_server reads config and, before the daemon process is allowed to start,
 * writes the waiting snapshot here. Other apps are rejected by calling uid.
 */
class ConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) return null
        val appContext = context ?: return null
        return when (method) {
            "load" -> loadBundle(appContext)
            "report" -> {
                if (uid != Process.SYSTEM_UID) return null
                val report = Report.fromBundle(extras ?: return null)
                runBlocking { ReportWriter.write(appContext, report) }
                Bundle()
            }
            else -> null
        }
    }

    private fun loadBundle(appContext: android.content.Context): Bundle {
        val config = ConfigStore(appContext).load()
        return Bundle().apply {
            putBoolean("master", config.master)
            putInt("interval", config.intervalSec)
            putInt("bootDelay", config.bootDelaySec)
            putInt("retention", config.retention)
            putString("token", config.token)
            putString("apps", ConfigCodec.appsToJson(config.apps))
            putString("a11y", ConfigCodec.stringsToJson(config.a11y))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

internal fun Bundle.toLoadedConfig(): LoadedConfig {
    return LoadedConfig(
        master = getBoolean("master", false),
        intervalSec = Ranges.intervalSec(getInt("interval", 20)),
        bootDelaySec = Ranges.bootDelaySec(getInt("bootDelay", 20)),
        retention = Ranges.retention(getInt("retention", 300)),
        token = getString("token").orEmpty(),
        apps = ConfigCodec.appsFromJson(getString("apps")),
        a11y = ConfigCodec.stringsFromJson(getString("a11y")),
    )
}

internal data class LoadedConfig(
    val master: Boolean,
    val intervalSec: Int,
    val bootDelaySec: Int,
    val retention: Int,
    val token: String,
    val apps: List<com.minedie.keepalive.core.GuardedApp>,
    val a11y: Set<String>,
)
