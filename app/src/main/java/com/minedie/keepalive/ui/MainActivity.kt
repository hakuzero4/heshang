package com.minedie.keepalive.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.minedie.keepalive.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minedie.keepalive.ACTION_DATA
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: KeepAliveViewModel by viewModels()
    private var offeredAppList = false
    private var askedApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(PageBg.toArgb(), PageBg.toArgb()),
            navigationBarStyle = SystemBarStyle.light(Color.White.toArgb(), Color.White.toArgb()),
        )
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            var tab by remember { mutableStateOf(Tab.Overview) }
            var openStarts by remember { mutableStateOf(false) }
            var showServices by remember { mutableStateOf(false) }
            DisposableEffect(this) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        model.refresh()
                    }
                }
                val filter = IntentFilter(ACTION_DATA)
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
                onDispose { unregisterReceiver(receiver) }
            }
            LaunchedEffect(Unit) {
                while (true) {
                    model.refresh()
                    delay(2000)
                }
            }
            LaunchedEffect(tab, state.appListLimited) {
                if (tab == Tab.Guard && state.appListLimited) offerAppList()
            }
            KeepAliveTheme {
                BackHandler(enabled = showServices) { showServices = false }
                Scaffold(
                    containerColor = PageBg,
                    bottomBar = {
                        NavigationBar(containerColor = Color.White) {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = {
                                        if (item == Tab.Log) openStarts = false
                                        showServices = false
                                        tab = item
                                    },
                                    icon = { Icon(painterResource(item.icon), contentDescription = item.label) },
                                    label = { Text(item.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Blue,
                                        selectedTextColor = Blue,
                                        unselectedIconColor = Muted,
                                        unselectedTextColor = Muted,
                                        indicatorColor = Color(0xFFE8F1FF),
                                    ),
                                )
                            }
                        }
                    },
                ) { padding ->
                    val body = Modifier.padding(padding)
                    if (showServices) {
                        androidx.compose.foundation.layout.Box(body) {
                            RunningServicesScreen(state, onBack = { showServices = false })
                        }
                    } else when (tab) {
                        Tab.Overview -> androidx.compose.foundation.layout.Box(body) {
                            OverviewScreen(
                                state,
                                model::setMaster,
                                onOpenGuard = { tab = Tab.Guard },
                                onOpenStarts = {
                                    openStarts = true
                                    tab = Tab.Log
                                },
                                onOpenA11y = { tab = Tab.A11y },
                                onOpenSettings = { tab = Tab.Settings },
                            )
                        }
                        Tab.Guard -> androidx.compose.foundation.layout.Box(body) {
                            GuardScreen(
                                state,
                                model::setShowSystem,
                                model::setApp,
                                model::appServices,
                                model::retry,
                                onOpenServices = { showServices = true },
                                onRequestApps = { askForAppList() },
                            )
                        }
                        Tab.A11y -> androidx.compose.foundation.layout.Box(body) {
                            A11yScreen(state, model::setA11y)
                        }
                        Tab.Log -> androidx.compose.foundation.layout.Box(body) {
                            LogScreen(state, openStarts)
                        }
                        Tab.Settings -> androidx.compose.foundation.layout.Box(body) {
                            SettingsScreen(
                                state,
                                model::setMaster,
                                model::setInterval,
                                model::setBootDelay,
                                model::setRetention,
                                model::patrolNow,
                                model::clearLogs,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    override fun onStop() {
        model.onBackground()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        model.refresh(reloadApps = true)
    }

    private fun offerAppList() {
        if (offeredAppList) return
        offeredAppList = true
        askForAppList()
    }

    private fun askForAppList() {
        if (!installedAppsPermissionKnown()) return
        if (checkSelfPermission(GET_INSTALLED_APPS) == PackageManager.PERMISSION_GRANTED) {
            model.refresh(reloadApps = true)
            return
        }
        if (askedApps && !shouldShowRequestPermissionRationale(GET_INSTALLED_APPS)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null)),
            )
            return
        }
        askedApps = true
        requestPermissions(arrayOf(GET_INSTALLED_APPS), 1)
    }

    private fun installedAppsPermissionKnown(): Boolean {
        return runCatching { packageManager.getPermissionInfo(GET_INSTALLED_APPS, 0) }.isSuccess
    }

    private companion object {
        const val GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"
    }
}

private enum class Tab(val label: String, val icon: Int) {
    Overview("总览", R.drawable.ic_nav_home),
    Guard("守护", R.drawable.ic_nav_shield),
    A11y("无障碍", R.drawable.ic_nav_a11y),
    Log("日志", R.drawable.ic_nav_log),
    Settings("设置", R.drawable.ic_nav_settings),
}
