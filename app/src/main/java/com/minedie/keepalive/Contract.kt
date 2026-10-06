package com.minedie.keepalive

internal val APP_PACKAGE = BuildConfig.APPLICATION_ID
internal const val PREFS_NAME = "keepalive_config"
internal val DAEMON_PROCESS = "${BuildConfig.APPLICATION_ID}:daemon"
internal val ACTION_CONTROL = "${BuildConfig.APPLICATION_ID}.action.CONTROL"
internal val ACTION_DATA = "${BuildConfig.APPLICATION_ID}.DATA_CHANGED"
internal val CONFIG_AUTHORITY = "${BuildConfig.APPLICATION_ID}.config"
internal const val DAEMON_CLASS = "com.minedie.keepalive.daemon.DaemonService"
