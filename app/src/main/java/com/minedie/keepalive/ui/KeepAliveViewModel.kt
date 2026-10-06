package com.minedie.keepalive.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minedie.keepalive.ACTION_CONTROL
import com.minedie.keepalive.BuildConfig
import com.minedie.keepalive.config.ConfigCodec
import com.minedie.keepalive.config.ConfigStore
import com.minedie.keepalive.core.EventLog
import com.minedie.keepalive.core.EventType
import com.minedie.keepalive.core.GuardedApp
import com.minedie.keepalive.core.LogRow
import com.minedie.keepalive.core.Phase
import com.minedie.keepalive.core.StatusInput
import com.minedie.keepalive.core.StatusMachine
import com.minedie.keepalive.data.EventEntity
import com.minedie.keepalive.data.KeepAliveDb
import com.minedie.keepalive.data.ReportWriter
import com.minedie.keepalive.xposed.ModuleProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServiceChoice(
    val component: String,
    val label: String,
    val className: String,
    val foreground: Boolean,
)

data class AppRow(
    val packageName: String,
    val label: String,
    val component: String,
    val enabled: Boolean,
    val running: Boolean,
    val system: Boolean,
    val gaveUp: Boolean,
)

data class LiveService(
    val packageName: String,
    val className: String,
    val processName: String,
)

data class A11yRow(
    val id: String,
    val label: String,
    val enabled: Boolean,
)

data class UiState(
    val statusTitle: String = "模块未激活",
    val statusSubtitle: String = "在 LSPosed 勾选系统框架和本模块",
    val healthy: Boolean = false,
    val warning: String? = null,
    val master: Boolean = false,
    val intervalSec: Int = 20,
    val bootDelaySec: Int = 20,
    val retention: Int = 300,
    val protectedCount: Int = 0,
    val runningCount: Int = 0,
    val silentStarts: Int = 0,
    val a11yCount: Int = 0,
    val recent: List<EventEntity> = emptyList(),
    val logs: List<EventEntity> = emptyList(),
    val apps: List<AppRow> = emptyList(),
    val services: List<A11yRow> = emptyList(),
    val showSystem: Boolean = false,
    val liveServices: List<LiveService> = emptyList(),
    val liveServicesKnown: Boolean = false,
    val versionName: String = BuildConfig.VERSION_NAME,
)

class KeepAliveViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ConfigStore(app)
    private val db = KeepAliveDb.get(app)
    private val stateFlow = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = stateFlow
    private var installed: List<RawApp> = emptyList()
    private var installedAt = 0L

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { readState() }
            stateFlow.value = snapshot
        }
    }

    fun setMaster(enabled: Boolean) {
        store.setMaster(enabled)
        send("rescan")
        refresh()
    }

    fun setInterval(seconds: Int) {
        store.setInterval(seconds)
        send("rescan")
        refresh()
    }

    fun setBootDelay(seconds: Int) {
        store.setBootDelay(seconds)
        send("rescan")
        refresh()
    }

    fun setRetention(count: Int) {
        store.setRetention(count)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ReportWriter.trim(getApplication(), store.load().retention) }
            send("rescan")
            refresh()
        }
    }

    fun appServices(packageName: String): List<ServiceChoice> {
        val pm = getApplication<Application>().packageManager
        val flags = PackageManager.GET_SERVICES
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        return info.services.orEmpty().map { service ->
            val label = runCatching { service.loadLabel(pm).toString() }.getOrDefault("").ifBlank {
                service.name.substringAfterLast('.')
            }
            ServiceChoice(
                component = ComponentName(packageName, service.name).flattenToString(),
                label = label,
                className = service.name.substringAfterLast('.'),
                foreground = service.foregroundServiceType != android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun setShowSystem(show: Boolean) {
        store.setShowSystem(show)
        refresh()
    }

    fun setApp(packageName: String, label: String, enabled: Boolean, component: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = store.load().apps.toMutableList()
            val index = current.indexOfFirst { it.packageName == packageName }
            val updated = GuardedApp(packageName, label, component.trim(), enabled)
            if (index >= 0) current[index] = updated else current += updated
            store.setApps(current)
            send("rescan")
            refresh()
        }
    }

    fun setA11y(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = store.load().a11y.toMutableSet()
            if (enabled) next.add(id) else next.remove(id)
            store.setA11y(next)
            send("rescan")
            refresh()
        }
    }

    fun restartDaemon() {
        send("restart")
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ReportWriter.clear(getApplication()) }
            refresh()
        }
    }

    fun retry(packageName: String) {
        send("retry", packageName)
    }

    private suspend fun readState(): UiState {
        val app = getApplication<Application>()
        val config = store.load()
        val now = System.currentTimeMillis()
        if (now - installedAt > 30_000L || installed.isEmpty()) {
            installed = loadInstalled(app)
            installedAt = now
        }
        val services = loadServices(app)
        val snap = db.snapshot().get()
        val logs = db.events().all()
        val recent = db.events().latest(8)
        val running = snap?.runningPackages.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val gaveUpPackages = snap?.gaveUpPackages.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val serviceJson = snap?.runningServices.orEmpty()
        val liveServices = if (serviceJson.isBlank()) {
            emptyList()
        } else {
            ConfigCodec.servicesFromJson(serviceJson).map { (pkg, cls, proc) ->
                LiveService(pkg, cls, proc)
            }
        }
        val enabledApps = config.apps.filter { it.enabled }
        val guarded = config.apps.associateBy { it.packageName }
        val rows = installed.map { raw ->
            val saved = guarded[raw.packageName]
            AppRow(
                packageName = raw.packageName,
                label = raw.label,
                component = saved?.component.orEmpty(),
                enabled = saved?.enabled == true,
                running = raw.packageName in running,
                system = raw.system,
                gaveUp = raw.packageName in gaveUpPackages,
            )
        }
        val logRows = logs.mapNotNull { entity ->
            val type = runCatching { EventType.valueOf(entity.type) }.getOrNull() ?: return@mapNotNull null
            LogRow(entity.id, entity.createdAt, type)
        }
        val phase = snap?.phase?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: Phase.PAUSED
        val age = snap?.let { now - it.reportedAt }
        val status = StatusMachine.derive(
            StatusInput(
                moduleActive = ModuleProbe.isActive(),
                masterEnabled = config.master,
                phase = phase,
                snapshotAgeMs = age,
                intervalMs = config.intervalSec * 1000L,
                daemonPid = snap?.daemonPid ?: 0,
                watchdogPullsLastHour = snap?.pullsLastHour ?: 0,
            ),
        )
        return UiState(
            statusTitle = status.title,
            statusSubtitle = status.subtitle,
            healthy = status.healthy,
            warning = status.warning ?: snap?.hookError,
            master = config.master,
            intervalSec = config.intervalSec,
            bootDelaySec = config.bootDelaySec,
            retention = config.retention,
            protectedCount = enabledApps.size,
            runningCount = enabledApps.count { it.packageName in running },
            silentStarts = EventLog.countSince(logRows, EventType.SILENT_START_OK, now, DAY),
            a11yCount = config.a11y.size,
            recent = recent,
            logs = logs,
            apps = rows,
            services = services.map { A11yRow(it.id, it.label, it.id in config.a11y) },
            showSystem = config.showSystem,
            liveServices = liveServices,
            liveServicesKnown = serviceJson.isNotBlank(),
        )
    }

    private fun send(op: String, packageName: String? = null) {
        val app = getApplication<Application>()
        val token = store.ensureToken()
        val intent = Intent(ACTION_CONTROL).putExtra("op", op).putExtra("token", token)
        if (packageName != null) intent.putExtra("package", packageName)
        app.sendBroadcast(intent)
    }

    private data class RawApp(val packageName: String, val label: String, val system: Boolean)

    private data class RawService(val id: String, val label: String)

    private fun loadInstalled(app: Application): List<RawApp> {
        val pm = app.packageManager
        return pm.getInstalledApplications(PackageManager.MATCH_ALL)
            .map { info ->
                RawApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun loadServices(app: Application): List<RawService> {
        val manager = app.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        return manager.installedAccessibilityServiceList.map { service ->
            RawService(
                id = service.id,
                label = service.resolveInfo.loadLabel(app.packageManager).toString(),
            )
        }.sortedBy { it.label.lowercase() }
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
