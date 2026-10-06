package com.minedie.keepalive.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.minedie.keepalive.R
import com.minedie.keepalive.config.ConfigStore
import com.minedie.keepalive.data.Report
import com.minedie.keepalive.data.ReportWriter
import kotlinx.coroutines.runBlocking

class DaemonService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val token = intent?.getStringExtra("token").orEmpty()
        val expected = ConfigStore(this).ensureToken()
        // onStartCommand runs after the system binder call returns, so getCallingUid()
        // is this app, not system_server. The private token is the check.
        if (token.isBlank() || token != expected) {
            Log.i("KeepAlive", "daemon rejected")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val report = Report.fromBundle(intent?.extras ?: return START_NOT_STICKY)
        runBlocking { ReportWriter.write(this@DaemonService, report) }
        if (report.stop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "守护进程", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("守护进程运行中")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL = "keepalive"
        const val NOTIFICATION_ID = 1001
    }
}
