package com.minedie.keepalive.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.minedie.keepalive.ACTION_CONTROL
import com.minedie.keepalive.CONFIG_AUTHORITY
import com.minedie.keepalive.DAEMON_CLASS
import com.minedie.keepalive.DAEMON_PROCESS
import com.minedie.keepalive.APP_PACKAGE
import com.minedie.keepalive.config.LoadedConfig
import com.minedie.keepalive.config.toLoadedConfig
import com.minedie.keepalive.core.AppMemory
import com.minedie.keepalive.core.EventDraft
import com.minedie.keepalive.core.EventText
import com.minedie.keepalive.core.EventType
import com.minedie.keepalive.core.GuardedApp
import com.minedie.keepalive.core.PatrolPolicy
import com.minedie.keepalive.core.Phase
import com.minedie.keepalive.core.StartupAllow
import com.minedie.keepalive.core.isPackageAlive
import com.minedie.keepalive.data.Report
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

internal object Watchdog {
    @Volatile var bootCompleted: Boolean = false
    @Volatile var cached: LoadedConfig? = null

    private var ams: Any? = null
    private var handler: Handler? = null
    private var classLoader: ClassLoader? = null
    private var bootElapsed: Long = 0
    private var started = false
    private var daemonStartedBefore = false
    private var memory: Map<String, AppMemory> = emptyMap()
    private var components: Map<String, String> = emptyMap()
    private val touched = mutableSetOf<String>()
    private val adjLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingEvents = ConcurrentLinkedQueue<EventDraft>()
    private val pulls = ArrayDeque<Long>()
    private var hookError: String? = null
    private var defaultAdj = 1001
    private var appContext: Context? = null

    private val runnable = Runnable { tick() }

    fun onAms(instance: Any) {
        ams = instance
    }

    fun onBootCompleted(loader: ClassLoader) {
        if (started) return
        val ams = ams
        if (ams == null) {
            noteHookError("未捕获 ActivityManagerService")
            return
        }
        val context = systemContext(ams)
        if (context == null) {
            noteHookError("未拿到系统 Context")
            return
        }
        classLoader = loader
        defaultAdj = Adj.readDefault(loader)
        bootCompleted = true
        bootElapsed = SystemClock.elapsedRealtime()
        appContext = context
        handler = Handler(Looper.getMainLooper())
        registerControl(context)
        started = true
        KLog.i("watchdog started")
        handler?.post(runnable)
    }

    fun noteAdj(packageName: String, label: String) {
        if (adjLogged.add(packageName)) {
            pendingEvents.add(EventText.adjApplied(label, packageName))
        }
    }

    /** True only for the exact service stored on an enabled app while the master switch is on. */
    fun allowsStart(packageName: String, className: String): Boolean {
        return try {
            val config = cached ?: return false
            StartupAllow.matches(config.master, config.apps, packageName, className)
        } catch (_: Throwable) {
            false
        }
    }

    fun noteHookError(message: String) {
        KLog.i(message)
        if (hookError == null) {
            hookError = message
            pendingEvents.add(EventText.hookFail(message))
        }
    }

    private fun registerControl(context: Context) {
        val filter = IntentFilter(ACTION_CONTROL)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val config = cached ?: loadConfig(ctx) ?: return
                if (intent.getStringExtra("token") != config.token) return
                when (intent.getStringExtra("op")) {
                    "restart" -> restart()
                    "rescan" -> {
                        memory = PatrolPolicy.resetGiveUps(memory)
                        handler?.removeCallbacks(runnable)
                        handler?.post(runnable)
                    }
                    "retry" -> {
                        val pkg = intent.getStringExtra("package")
                        if (!pkg.isNullOrEmpty()) {
                            memory = PatrolPolicy.retry(memory, pkg)
                            handler?.removeCallbacks(runnable)
                            handler?.post(runnable)
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private fun restart() {
        val pid = daemonPid(ProcessTable.read(ams ?: return))
        if (pid > 0) {
            try {
                android.os.Process.killProcess(pid)
            } catch (error: Throwable) {
                KLog.i("kill daemon failed: ${error.javaClass.simpleName}")
            }
        }
        daemonStartedBefore = true
        handler?.removeCallbacks(runnable)
        handler?.postDelayed(runnable, 500)
        KLog.i("restart requested")
    }

    private fun tick() {
        val context = appContext ?: return
        val ams = ams ?: return
        try {
            val config = loadConfig(context)
            if (config == null) {
                KLog.i("config unavailable")
                schedule(20)
                return
            }
            cached = config
            syncComponents(config.apps)
            val delayElapsed = SystemClock.elapsedRealtime() - bootElapsed >= config.bootDelaySec * 1000L
            val bootReady = delayElapsed && isUserUnlocked(context)
            val rows = ProcessTable.read(ams)
            val names = rows.map { it.processName }.toSet()
            val enabled = config.apps.filter { it.enabled }
            val alive = enabled.map { it.packageName }.filter { isPackageAlive(names, it) }.toSet()
            val daemonPid = daemonPid(rows)
            val table = ServiceTable.read(ams)
            val runningServices = if (table.trusted) {
                table.services.map { "${it.packageName}/${it.className}" }.toSet()
            } else {
                null
            }
            val decision = PatrolPolicy.decide(
                com.minedie.keepalive.core.PatrolInput(
                    masterEnabled = config.master,
                    bootReady = bootReady,
                    daemonAlive = daemonPid > 0,
                    daemonStartedBefore = daemonStartedBefore,
                    apps = enabled,
                    alivePackages = alive,
                    memory = memory,
                    guardedA11y = config.a11y,
                    enabledA11yRaw = if (bootReady && config.master) readA11y(context) else "",
                    runningServices = runningServices,
                ),
            )
            memory = decision.memory
            if (decision.markDaemonStarted) daemonStartedBefore = true
            if (bootCompleted) {
                applyAdj(ams, rows, enabled, decision.resetAdj, config.master)
            }
            val events = decision.events.toMutableList()
            val a11yValue = decision.a11yValue
            if (a11yValue != null) {
                if (writeA11y(context, a11yValue)) {
                    decision.a11yAdded.forEach { events += EventText.a11yRestored(it) }
                } else {
                    decision.a11yAdded.forEach { events += EventText.a11yFail(it) }
                }
            }
            val packageFailureNoted = mutableSetOf<String>()
            for (app in decision.startComponents) {
                val reason = startComponent(context, app.component)
                if (reason == null) continue
                val processLevel = memory[app.packageName]?.awaitingResult == true
                val (afterService, serviceEvent) = PatrolPolicy.noteServiceFailure(memory, app, reason = reason)
                memory = afterService
                if (processLevel && app.packageName !in packageFailureNoted) {
                    packageFailureNoted += app.packageName
                    val (next, event) = PatrolPolicy.noteImmediateFailure(memory, app, reason = reason)
                    memory = next
                    if (event != null) events += event
                } else if (serviceEvent != null) {
                    events += serviceEvent
                }
            }
            while (true) {
                val extra = pendingEvents.poll() ?: break
                events += extra
            }
            recordPulls(events)
            val enabledPackages = enabled.map { it.packageName }.toSet()
            val report = Report(
                phase = decision.phase,
                daemonPid = if (decision.stopDaemon) 0 else daemonPid,
                runningPackages = alive.toList(),
                pullsLastHour = pulls.size,
                hookError = hookError,
                events = events,
                retention = config.retention,
                stop = decision.stopDaemon,
                gaveUpPackages = memory.filter { (pkg, item) -> item.gaveUp && pkg in enabledPackages }.keys.toList(),
                runningServices = com.minedie.keepalive.config.ConfigCodec.servicesToJson(
                    table.services.map { Triple(it.packageName, it.className, it.processName) },
                ),
            )
            deliver(context, config, report)
            schedule(config.intervalSec)
        } catch (error: Throwable) {
            noteHookError("巡检失败: ${error.javaClass.simpleName}")
            schedule(20)
        }
    }

    private fun syncComponents(apps: List<GuardedApp>) {
        val next = apps.filter { it.enabled }.associate { it.packageName to it.targets().joinToString("\n") }
        for ((pkg, component) in next) {
            if (!PatrolPolicy.armStart(components[pkg], component)) continue
            val current = memory[pkg] ?: AppMemory()
            memory = memory + (pkg to current.copy(failures = 0, gaveUp = false, awaitingResult = false, forceRetry = true))
        }
        components = next
    }

    private fun applyAdj(
        ams: Any,
        rows: List<ProcessRow>,
        enabled: List<GuardedApp>,
        reset: Boolean,
        master: Boolean,
    ) {
        val protected = enabled.map { it.packageName }.toSet()
        try {
            synchronized(ams) {
                for (row in rows) {
                    val pkg = row.processName.substringBefore(':')
                    val keep = !reset && master && (pkg in protected || row.processName == DAEMON_PROCESS)
                    try {
                        if (keep) {
                            Adj.setMax(row.record, Adj.PERCEPTIBLE)
                            touched += row.processName
                            val label = enabled.firstOrNull { it.packageName == pkg }?.label
                            if (label != null) noteAdj(pkg, label)
                        } else if (row.processName in touched) {
                            Adj.setMax(row.record, defaultAdj)
                            touched.remove(row.processName)
                        }
                    } catch (error: Throwable) {
                        noteHookError("setMaxAdj 失败: ${error.javaClass.simpleName}")
                    }
                }
            }
        } catch (error: Throwable) {
            noteHookError("应用 adj 失败: ${error.javaClass.simpleName}")
        }
    }

    private fun deliver(context: Context, config: LoadedConfig, report: Report) {
        // ColorOS freezes the app uid, including :daemon. The provider call runs from
        // system_server and is what actually gets the event log written while frozen.
        val wrote = writeReport(context, report)
        val useDaemon = (config.master && report.phase != Phase.WAITING_BOOT) || report.stop
        if (!useDaemon) return
        val intent = Intent().setClassName(APP_PACKAGE, DAEMON_CLASS)
        intent.putExtra("token", config.token)
        if (wrote) {
            intent.putExtra("op", if (report.stop) "stop" else "hold")
        } else {
            intent.putExtra("op", "report")
            intent.putExtras(report.toBundle())
        }
        try {
            context.startService(intent)
        } catch (error: Throwable) {
            noteHookError("启动守护进程失败: ${error.javaClass.simpleName}")
        }
    }

    private fun writeReport(context: Context, report: Report): Boolean {
        return try {
            context.contentResolver.call(CONFIG_AUTHORITY, "report", null, report.toBundle()) != null
        } catch (error: Throwable) {
            KLog.i("provider report failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun loadConfig(context: Context): LoadedConfig? {
        return try {
            val bundle = context.contentResolver.call(CONFIG_AUTHORITY, "load", null, null) ?: return null
            bundle.toLoadedConfig()
        } catch (error: Throwable) {
            KLog.i("load config failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun readA11y(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun writeA11y(context: Context, value: String): Boolean {
        return try {
            val wrote = Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                value,
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            wrote
        } catch (error: Throwable) {
            KLog.i("a11y write failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun isUserUnlocked(context: Context): Boolean {
        return try {
            context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        } catch (error: Throwable) {
            KLog.i("unlock check failed: ${error.javaClass.simpleName}")
            true
        }
    }

    /** Null means the service was started. A short reason means it was refused. */
    private fun startComponent(context: Context, flat: String): String? {
        val component = android.content.ComponentName.unflattenFromString(flat) ?: return "组件无效"
        val identity = Binder.clearCallingIdentity()
        try {
            val info = context.packageManager.getServiceInfo(component, 0)
            val foreground = info.foregroundServiceType != 0
            val intent = Intent().setComponent(component)
            val direct = runCatching { context.startService(intent) }
            if (direct.isSuccess && direct.getOrNull() != null) return null
            val appUid = context.packageManager.getApplicationInfo(component.packageName, 0).uid
            if (startAsOwner(intent, component.packageName, appUid, foreground)) return null
            if (foreground) {
                val promoted = runCatching { context.startForegroundService(intent) }
                if (promoted.isSuccess && promoted.getOrNull() != null) return null
                if (startAsOwner(intent, component.packageName, appUid, true)) return null
                return reasonOf(promoted.exceptionOrNull() ?: direct.exceptionOrNull())
            }
            return reasonOf(direct.exceptionOrNull())
        } catch (error: Throwable) {
            val reason = reasonOf(error)
            KLog.i("silent start refused $flat: $reason")
            return reason
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun startAsOwner(intent: Intent, packageName: String, appUid: Int, foreground: Boolean): Boolean {
        val loader = classLoader ?: return false
        return try {
            val internalClass = Class.forName("android.app.ActivityManagerInternal", false, loader)
            val localServices = Class.forName("com.android.server.LocalServices", false, loader)
            val internal = localServices.getMethod("getService", Class::class.java).invoke(null, internalClass)
                ?: return false
            val method = internal.javaClass.methods.firstOrNull {
                it.name == "startServiceInPackage" && (it.parameterTypes.size == 7 || it.parameterTypes.size == 6)
            } ?: return false
            method.isAccessible = true
            val userId = appUid / 100_000
            val started = if (method.parameterTypes.size == 7) {
                method.invoke(internal, appUid, intent, null, foreground, packageName, null, userId)
            } else {
                method.invoke(internal, appUid, intent, null, foreground, packageName, userId)
            }
            started != null
        } catch (error: Throwable) {
            KLog.i("start as owner refused: ${reasonOf(error)}")
            false
        }
    }

    private fun reasonOf(error: Throwable?): String {
        val root = generateSequence(error) { current ->
            (current as? java.lang.reflect.InvocationTargetException)?.targetException
        }.lastOrNull()
        if (root == null) {
            KLog.i("silent start refused: 被系统拦截")
            return "被系统拦截"
        }
        val name = root.javaClass.simpleName.ifBlank { "未能启动" }
        KLog.i("silent start refused: $name")
        return name
    }

    private fun recordPulls(events: List<EventDraft>) {
        val now = System.currentTimeMillis()
        if (events.any { it.type == EventType.WATCHDOG_PULLED_DAEMON }) {
            pulls.addLast(now)
        }
        val hour = 60 * 60 * 1000L
        while (pulls.isNotEmpty() && now - pulls.first() > hour) pulls.removeFirst()
    }

    private fun daemonPid(rows: List<ProcessRow>): Int {
        return rows.firstOrNull { it.processName == DAEMON_PROCESS }?.pid ?: 0
    }

    private fun schedule(seconds: Int) {
        val delay = seconds.coerceAtLeast(5) * 1000L
        handler?.removeCallbacks(runnable)
        handler?.postDelayed(runnable, delay)
    }

    private fun systemContext(ams: Any): Context? {
        return try {
            XposedHelpers.getObjectField(ams, "mContext") as? Context
        } catch (_: Throwable) {
            null
        }
    }
}

internal object KLog {
    fun i(message: String) {
        try {
            XposedBridge.log("[KeepAlive] $message")
        } catch (_: Throwable) {
        }
        try {
            android.util.Log.i("KeepAlive", message)
        } catch (_: Throwable) {
        }
    }
}
