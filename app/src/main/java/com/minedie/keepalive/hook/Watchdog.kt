package com.minedie.keepalive.hook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.minedie.keepalive.ACTION_CONTROL
import com.minedie.keepalive.APP_PACKAGE
import com.minedie.keepalive.CONTROL_PERMISSION
import com.minedie.keepalive.config.ConfigCodec
import com.minedie.keepalive.config.LoadedConfig
import com.minedie.keepalive.core.AppMemory
import com.minedie.keepalive.core.EventDraft
import com.minedie.keepalive.core.EventText
import com.minedie.keepalive.core.GuardedApp
import com.minedie.keepalive.core.PatrolInput
import com.minedie.keepalive.core.PatrolPolicy
import com.minedie.keepalive.core.StartupAllow
import com.minedie.keepalive.core.isPackageAlive
import com.minedie.keepalive.data.Report
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Patrol runs entirely inside system_server. LSPosed injects this hook at boot.
 * The saved config is a file this process writes under /data/system, so a reboot
 * can read it without starting the app. ColorOS freezes this app's uid while the
 * UI is gone, and a binder call into that uid would stall the patrol, so the tick
 * never calls back.
 */
internal object Watchdog {
    @Volatile var bootCompleted: Boolean = false
    @Volatile var cached: LoadedConfig? = null

    private var ams: Any? = null
    private var handler: Handler? = null
    private var classLoader: ClassLoader? = null
    private var bootElapsed: Long = 0
    private var started = false
    private var memory: Map<String, AppMemory> = emptyMap()
    private var components: Map<String, String> = emptyMap()
    private val touched = mutableSetOf<String>()
    private val adjLogged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingEvents = ConcurrentLinkedQueue<EventDraft>()
    @Volatile private var hookError: String? = null
    private var latest: Report? = null
    private var defaultAdj = 1001
    private var appContext: Context? = null
    private var reportedConfig = false
    private val systemConfigFile = File("/data/system/$APP_PACKAGE/config.json")

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
        appContext = context
        cached = loadConfig()
        bootCompleted = true
        bootElapsed = SystemClock.elapsedRealtime()
        val thread = HandlerThread("heshang-patrol")
        thread.start()
        val watchHandler = Handler(thread.looper)
        handler = watchHandler
        registerControl(context, watchHandler)
        started = true
        KLog.i("watchdog started")
        watchHandler.post(runnable)
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

    private fun registerControl(context: Context, watchHandler: Handler) {
        val filter = IntentFilter(ACTION_CONTROL)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!sentByApp(this)) return
                when (intent.getStringExtra("op")) {
                    "pull" -> reply(this)
                    "retry" -> {
                        if (!accept(ctx, intent)) return
                        val pkg = intent.getStringExtra("package")
                        if (!pkg.isNullOrEmpty()) memory = PatrolPolicy.retry(memory, pkg)
                        tick()
                    }
                    "rescan", "restart" -> {
                        if (!accept(ctx, intent)) return
                        memory = PatrolPolicy.resetGiveUps(memory)
                        tick()
                    }
                    else -> {
                        if (!accept(ctx, intent)) return
                        watchHandler.removeCallbacks(runnable)
                        watchHandler.post(runnable)
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    CONTROL_PERMISSION,
                    watchHandler,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                context.registerReceiver(receiver, filter, CONTROL_PERMISSION, watchHandler)
            }
            KLog.i("control receiver registered in ${context.packageName}")
        } catch (error: Throwable) {
            noteHookError("控制通道注册失败: ${error.javaClass.simpleName}")
        }
    }

    private fun sentByApp(receiver: BroadcastReceiver): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val pkg = receiver.sentFromPackage ?: return true
        return pkg == APP_PACKAGE
    }

    /** A push carries the app's copy. Pull must not write; the app adopts this file when its own is empty. */
    private fun accept(context: Context, intent: Intent): Boolean {
        val incoming = ConfigCodec.configFromJson(intent.getStringExtra("config"))
        if (incoming != null) {
            if (intent.getStringExtra("token") != incoming.token) return false
            cached = incoming
            writeSystemConfig(incoming)
            return true
        }
        val known = cached ?: loadConfig() ?: return false
        if (known.token.isBlank() || intent.getStringExtra("token") != known.token) return false
        cached = known
        return true
    }

    private fun reply(receiver: BroadcastReceiver) {
        try {
            val bundle = latest?.copy(events = drainEvents())?.toBundle() ?: android.os.Bundle()
            val config = loadConfig()
            if (config != null) bundle.putString("config", ConfigCodec.configToJson(config))
            receiver.setResult(Activity.RESULT_OK, null, bundle)
        } catch (error: Throwable) {
            noteHookError("回传状态失败: ${error.javaClass.simpleName}")
        }
    }

    private fun tick() {
        val context = appContext ?: return
        val ams = ams ?: return
        try {
            val config = loadConfig()
            if (config == null) {
                KLog.i("config unavailable")
                schedule(20)
                return
            }
            cached = config
            syncComponents(config.apps)
            val delayElapsed = SystemClock.elapsedRealtime() - bootElapsed >= config.bootDelaySec * 1000L
            val bootReady = delayElapsed && isUserUnlocked(context)
            val enabled = config.apps.filter { it.enabled }
            val rows = ProcessTable.read(ams)
            val names = rows.map { it.processName }.toSet()
            val alive = enabled.map { it.packageName }.filter { isPackageAlive(names, it) }.toSet()
            val table = ServiceTable.read(ams)
            val runningServices = if (table.trusted) {
                table.services.map { "${it.packageName}/${it.className}" }.toSet()
            } else {
                null
            }
            val decision = PatrolPolicy.decide(
                PatrolInput(
                    masterEnabled = config.master,
                    bootReady = bootReady,
                    apps = enabled,
                    alivePackages = alive,
                    memory = memory,
                    guardedA11y = config.a11y,
                    enabledA11yRaw = if (bootReady && config.master) readA11y(context) else "",
                    runningServices = runningServices,
                ),
            )
            memory = decision.memory
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
            enqueue(events, config.retention)
            val enabledPackages = enabled.map { it.packageName }.toSet()
            latest = Report(
                phase = decision.phase,
                daemonPid = 0,
                runningPackages = alive.toList(),
                pullsLastHour = 0,
                hookError = hookError,
                events = emptyList(),
                retention = config.retention,
                stop = false,
                gaveUpPackages = memory.filter { (pkg, item) -> item.gaveUp && pkg in enabledPackages }.keys.toList(),
                runningServices = ConfigCodec.servicesToJson(
                    table.services.map { Triple(it.packageName, it.className, it.processName) },
                ),
            )
            schedule(config.intervalSec)
        } catch (error: Throwable) {
            noteHookError("巡检失败: ${error.javaClass.simpleName}")
            schedule(20)
        }
    }

    private fun enqueue(events: List<EventDraft>, cap: Int) {
        for (event in events) pendingEvents.add(event)
        val limit = cap.coerceAtLeast(1)
        while (pendingEvents.size > limit) pendingEvents.poll()
    }

    private fun drainEvents(): List<EventDraft> {
        val out = ArrayList<EventDraft>()
        while (true) {
            out += pendingEvents.poll() ?: break
        }
        return out
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
                    val keep = !reset && master && pkg in protected
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

    /**
     * system_server owns this file. The app json is mode 600 and not readable here.
     * A push from the app writes it; every later boot reads it without starting the app.
     */
    private fun loadConfig(): LoadedConfig? {
        val fresh = readSystemConfig()
        if (fresh != null) {
            cached = fresh
            return fresh
        }
        return cached
    }

    private fun readSystemConfig(): LoadedConfig? {
        return try {
            if (!systemConfigFile.isFile) {
                if (!reportedConfig) {
                    KLog.i("system config missing ${systemConfigFile.absolutePath}")
                    reportedConfig = true
                }
                return null
            }
            val loaded = ConfigCodec.configFromJson(systemConfigFile.readText())
            if (!reportedConfig) {
                KLog.i("system config read ${systemConfigFile.absolutePath} ok=${loaded != null}")
                reportedConfig = true
            }
            loaded
        } catch (error: Throwable) {
            KLog.i("system config read failed: ${error.javaClass.simpleName} ${error.message}")
            null
        }
    }

    private fun writeSystemConfig(config: LoadedConfig) {
        try {
            val directory = systemConfigFile.parentFile ?: return
            if (!directory.exists() && !directory.mkdirs()) {
                KLog.i("system config directory missing ${directory.absolutePath}")
                return
            }
            val temporary = File(directory, "${systemConfigFile.name}.tmp")
            temporary.writeText(ConfigCodec.configToJson(config))
            if (!temporary.renameTo(systemConfigFile)) {
                systemConfigFile.writeText(ConfigCodec.configToJson(config))
                temporary.delete()
            }
            KLog.i("system config stored ${systemConfigFile.absolutePath}")
        } catch (error: Throwable) {
            KLog.i("system config write failed: ${error.javaClass.simpleName} ${error.message}")
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
        if (component.packageName == APP_PACKAGE) return "不拉起本模块"
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
