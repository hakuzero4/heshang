package com.minedie.keepalive.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.minedie.keepalive.ACTION_DATA
import com.minedie.keepalive.config.ConfigCodec
import com.minedie.keepalive.core.EventDraft
import com.minedie.keepalive.core.Phase
import com.minedie.keepalive.core.Ranges

internal data class Report(
    val phase: Phase,
    val daemonPid: Int,
    val runningPackages: List<String>,
    val pullsLastHour: Int,
    val hookError: String?,
    val events: List<EventDraft>,
    val retention: Int,
    val stop: Boolean,
    val gaveUpPackages: List<String>,
    val runningServices: String? = null,
) {
    fun toBundle(): Bundle {
        return Bundle().apply {
            putString("phase", phase.name)
            putInt("daemonPid", daemonPid)
            putString("running", runningPackages.joinToString(","))
            putInt("pulls", pullsLastHour)
            putString("hookError", hookError)
            putString("events", ConfigCodec.eventsToJson(events))
            putInt("retention", retention)
            putBoolean("stop", stop)
            putString("gaveUp", gaveUpPackages.joinToString(","))
            if (runningServices != null) putString("services", runningServices)
        }
    }

    companion object {
        fun fromBundle(bundle: Bundle): Report {
            val phase = runCatching { Phase.valueOf(bundle.getString("phase").orEmpty()) }
                .getOrDefault(Phase.PAUSED)
            val running = bundle.getString("running").orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
            val gaveUp = bundle.getString("gaveUp").orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
            val services = if (bundle.containsKey("services")) bundle.getString("services").orEmpty() else null
            return Report(
                phase = phase,
                daemonPid = bundle.getInt("daemonPid"),
                runningPackages = running,
                pullsLastHour = bundle.getInt("pulls"),
                hookError = bundle.getString("hookError")?.ifBlank { null },
                events = ConfigCodec.eventsFromJson(bundle.getString("events")),
                retention = Ranges.retention(bundle.getInt("retention", 300)),
                stop = bundle.getBoolean("stop"),
                gaveUpPackages = gaveUp,
                runningServices = services,
            )
        }
    }
}

internal object ReportWriter {
    suspend fun write(context: Context, report: Report) {
        val db = KeepAliveDb.get(context)
        val now = System.currentTimeMillis()
        val rows = report.events.map { event ->
            EventEntity(
                type = event.type.name,
                title = event.title,
                detail = event.detail,
                packageName = event.packageName,
                createdAt = now,
            )
        }
        db.events().insertAndTrim(rows, report.retention)
        db.snapshot().upsert(
            SnapshotEntity(
                phase = report.phase.name,
                daemonPid = report.daemonPid,
                reportedAt = now,
                runningPackages = report.runningPackages.joinToString(","),
                pullsLastHour = report.pullsLastHour,
                hookError = report.hookError,
                gaveUpPackages = report.gaveUpPackages.joinToString(","),
                runningServices = report.runningServices ?: "",
            ),
        )
        context.sendBroadcast(Intent(ACTION_DATA).setPackage(context.packageName))
    }

    suspend fun clear(context: Context) {
        KeepAliveDb.get(context).events().clear()
        context.sendBroadcast(Intent(ACTION_DATA).setPackage(context.packageName))
    }

    suspend fun trim(context: Context, retention: Int) {
        val dao = KeepAliveDb.get(context).events()
        val extra = dao.count() - Ranges.retention(retention)
        if (extra > 0) dao.deleteOldest(extra)
    }
}
