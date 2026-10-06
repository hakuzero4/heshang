package com.minedie.keepalive

import android.app.Application
import com.minedie.keepalive.config.ConfigStore

class KeepAliveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore(this).ensureToken()
    }
}
