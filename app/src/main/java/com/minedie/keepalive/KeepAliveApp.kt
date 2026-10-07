package com.minedie.keepalive

import android.app.Application
import com.minedie.keepalive.config.ConfigStore
import com.minedie.keepalive.config.ControlBus

class KeepAliveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = ConfigStore(this)
        ControlBus.pull(this) { _, remote ->
            if (remote != null && store.shouldAdopt(remote)) store.adopt(remote)
            // A missed reply must not publish a blank file over the system copy.
            if (remote == null && store.isBlank()) return@pull
            if (store.peekToken().isBlank()) store.ensureToken()
            ControlBus.send(this, "push")
        }
    }
}
