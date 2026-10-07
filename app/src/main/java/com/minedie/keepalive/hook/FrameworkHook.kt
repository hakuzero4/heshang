package com.minedie.keepalive.hook

import android.content.Intent
import com.minedie.keepalive.BuildConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FrameworkHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when (lpparam.packageName) {
                BuildConfig.APPLICATION_ID -> hookSelf(lpparam)
                "android" -> hookFramework(lpparam)
            }
        } catch (error: Throwable) {
            KLog.i("hook install failed: ${error.javaClass.simpleName}")
        }
    }

    private fun hookSelf(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            "com.minedie.keepalive.xposed.ModuleProbe",
            lpparam.classLoader,
            "isActive",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            },
        )
    }

    private fun hookFramework(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookStartupAllow(lpparam)
        val ams = XposedHelpers.findClassIfExists(
            "com.android.server.am.ActivityManagerService",
            lpparam.classLoader,
        )
        if (ams == null) {
            Watchdog.noteHookError("找不到 ActivityManagerService")
            return
        }
        XposedBridge.hookAllConstructors(
            ams,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Watchdog.onAms(param.thisObject)
                }
            },
        )
        XposedBridge.hookAllMethods(
            ams,
            "finishBooting",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    Watchdog.onBootCompleted(lpparam.classLoader)
                }
            },
        )
        val processList = XposedHelpers.findClassIfExists(
            "com.android.server.am.ProcessList",
            lpparam.classLoader,
        )
        if (processList == null) {
            Watchdog.noteHookError("找不到 ProcessList")
            return
        }
        XposedBridge.hookAllMethods(processList, "newProcessRecordLocked", NewProcessHook)
        KLog.i("framework hooks installed")
    }

    /**
     * ColorOS refuses startService from the system uid. Allow only the service the user picked.
     * A missing class or method is skipped. This does not touch athena, battery, bind, or broadcast checks.
     */
    private fun hookStartupAllow(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val manager = XposedHelpers.findClassIfExists(
                "com.android.server.am.OplusAppStartupManager",
                lpparam.classLoader,
            )
            if (manager == null) {
                KLog.i("startup allow hook skipped: class missing")
                return
            }
            val hooked = XposedBridge.hookAllMethods(
                manager,
                "isAllowStartFromStartService",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args?.lastOrNull { it is Intent } as? Intent ?: return
                            val component = intent.component ?: return
                            if (!Watchdog.allowsStart(component.packageName, component.className)) return
                            param.result = true
                            KLog.i("allow start ${component.packageName}/${component.className.substringAfterLast('.')}")
                        } catch (error: Throwable) {
                            KLog.i("startup allow check failed: ${error.javaClass.simpleName}")
                        }
                    }
                },
            )
            if (hooked.isEmpty()) {
                KLog.i("startup allow hook skipped: method missing")
            } else {
                KLog.i("startup allow hook installed")
            }
        } catch (error: Throwable) {
            KLog.i("startup allow hook skipped: ${error.javaClass.simpleName}")
        }
    }

    private object NewProcessHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                if (!Watchdog.bootCompleted) return
                val record = param.result ?: return
                val config = Watchdog.cached ?: return
                if (!config.master) return
                val processName = XposedHelpers.getObjectField(record, "processName") as? String ?: return
                val pkg = processName.substringBefore(':')
                val guarded = config.apps.firstOrNull { it.enabled && it.packageName == pkg } ?: return
                Adj.setMax(record, Adj.PERCEPTIBLE)
                Watchdog.noteAdj(guarded.packageName, guarded.label)
            } catch (error: Throwable) {
                Watchdog.noteHookError("新建进程 adj 失败: ${error.javaClass.simpleName}")
            }
        }
    }
}
