package com.minedie.keepalive

import android.app.Application
import com.minedie.keepalive.config.ConfigStore
import com.minedie.keepalive.config.ControlBus

class KeepAliveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore(this).ensureToken()
        ControlBus.send(this, "push")
    }
}
