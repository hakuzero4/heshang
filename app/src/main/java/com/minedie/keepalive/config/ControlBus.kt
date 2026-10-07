package com.minedie.keepalive.config

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.minedie.keepalive.ACTION_CONTROL
import com.minedie.keepalive.data.Report

/**
 * The app talks to the system-process watchdog. The watchdog never calls back:
 * ColorOS freezes this uid while the UI is closed, and a binder call from
 * system_server into a frozen uid blocks the patrol.
 */
internal object ControlBus {
    fun send(context: Context, op: String, packageName: String? = null) {
        context.sendBroadcast(intent(context, op, packageName))
    }

    fun pull(context: Context, onReport: (Report) -> Unit) {
        context.sendOrderedBroadcast(
            intent(context, "pull", null),
            null,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, data: Intent) {
                    if (resultCode != Activity.RESULT_OK) return
                    val extras = getResultExtras(false) ?: return
                    onReport(Report.fromBundle(extras))
                }
            },
            null,
            Activity.RESULT_CANCELED,
            null,
            null,
        )
    }

    private fun intent(context: Context, op: String, packageName: String?): Intent {
        val config = ConfigStore(context).load()
        return Intent(ACTION_CONTROL)
            .setPackage("android")
            .putExtra("op", op)
            .putExtra("token", config.token)
            .putExtra("config", ConfigCodec.configToJson(config))
            .apply {
                if (packageName != null) putExtra("package", packageName)
            }
    }
}
