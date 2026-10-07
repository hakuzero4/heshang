package com.minedie.keepalive.core

data class PatrolInput(
    val masterEnabled: Boolean,
    val bootReady: Boolean,
    val apps: List<GuardedApp>,
    val alivePackages: Set<String>,
    val memory: Map<String, AppMemory>,
    val guardedA11y: Set<String>,
    val enabledA11yRaw: String?,
    val failureCap: Int = 3,
    /** package/class keys of services with a process. Null means the table was not read. */
    val runningServices: Set<String>? = null,
)

data class PatrolOutput(
    val phase: Phase,
    val events: List<EventDraft>,
    val startComponents: List<GuardedApp>,
    val a11yValue: String?,
    val a11yAdded: List<String>,
    val memory: Map<String, AppMemory>,
    val resetAdj: Boolean,
)

/**
 * Patrol uses only data the system process already holds. It must not ask whether
 * this module's process is alive: ColorOS freezes that uid when the UI is closed,
 * and a binder call into it stalls the patrol before any guarded service starts.
 */
object PatrolPolicy {
    fun decide(input: PatrolInput): PatrolOutput {
        if (!input.masterEnabled) {
            return PatrolOutput(
                phase = Phase.PAUSED,
                events = emptyList(),
                startComponents = emptyList(),
                a11yValue = null,
                a11yAdded = emptyList(),
                memory = emptyMap(),
                resetAdj = true,
            )
        }
        if (!input.bootReady) {
            return PatrolOutput(
                phase = Phase.WAITING_BOOT,
                events = emptyList(),
                startComponents = emptyList(),
                a11yValue = null,
                a11yAdded = emptyList(),
                memory = input.memory,
                resetAdj = false,
            )
        }

        val events = mutableListOf<EventDraft>()
        val starts = mutableListOf<GuardedApp>()
        val nextMemory = linkedMapOf<String, AppMemory>()

        for (app in input.apps) {
            if (app.packageName.isBlank() || !app.enabled) continue
            val prev = input.memory[app.packageName] ?: AppMemory()
            val alive = app.packageName in input.alivePackages
            val targets = app.targets()
            var failures = prev.failures
            var gaveUp = prev.gaveUp
            val serviceFailures = prev.serviceFailures.toMutableMap()

            if (prev.awaitingResult && alive) {
                val started = prev.awaitingServices.ifEmpty { targets }
                events += EventText.startOk(app.label, app.packageName, started)
                failures = 0
                gaveUp = false
            } else if (prev.awaitingResult && !alive) {
                failures += 1
                gaveUp = failures >= input.failureCap
                val started = prev.awaitingServices.ifEmpty { targets }
                events += EventText.startFail(app.label, app.packageName, components = started)
            }
            if (alive && !prev.awaitingResult) {
                failures = 0
                gaveUp = false
            }

            val dropped = prev.wasAlive == true && !alive
            if (dropped) {
                events += EventText.lost(app.label, app.packageName, targets)
            }

            val known = input.runningServices
            val runningNow = if (known == null) {
                emptySet()
            } else {
                targets.filter { serviceRunning(known, app.packageName, it) }.toSet()
            }
            // A second service can die while the process stays up. Only count that when the table is known.
            if (alive && known != null && !prev.awaitingResult) {
                for (target in prev.awaitingServices) {
                    if (target !in targets) continue
                    if (target in runningNow) {
                        serviceFailures.remove(target)
                        events += EventText.startOk(app.label, app.packageName, listOf(target))
                    } else {
                        val count = (serviceFailures[target] ?: 0) + 1
                        serviceFailures[target] = count
                        events += EventText.startFail(app.label, app.packageName, components = listOf(target))
                    }
                }
            } else if (alive && known != null) {
                for (target in runningNow) serviceFailures.remove(target)
            }

            val wantProcess = !alive &&
                !gaveUp &&
                failures < input.failureCap &&
                targets.isNotEmpty() &&
                (dropped || prev.awaitingResult || prev.failures > 0 || prev.forceRetry)
            val launch = mutableListOf<String>()
            if (wantProcess) {
                launch += targets.filter { (serviceFailures[it] ?: 0) < input.failureCap }
            } else if (alive && !gaveUp && targets.isNotEmpty() && known != null) {
                for (target in targets) {
                    if (target in runningNow) continue
                    val count = serviceFailures[target] ?: 0
                    if (count >= input.failureCap) continue
                    val droppedService = target in prev.serviceSeen
                    if (droppedService) {
                        events += EventText.lost(app.label, app.packageName, listOf(target))
                    }
                    val missing = droppedService || target in prev.awaitingServices || count > 0
                    if (missing || prev.forceRetry) launch += target
                }
            } else if (alive && !gaveUp && prev.forceRetry && known == null) {
                launch += targets
            }
            val launched = launch.distinct()
            for (flat in launched) {
                starts += app.copy(component = flat)
            }
            nextMemory[app.packageName] = AppMemory(
                wasAlive = alive,
                failures = failures,
                gaveUp = gaveUp,
                awaitingResult = wantProcess && launched.isNotEmpty(),
                serviceSeen = if (known == null) prev.serviceSeen else runningNow,
                serviceFailures = serviceFailures,
                awaitingServices = when {
                    launched.isNotEmpty() -> launched.toSet()
                    known == null -> prev.awaitingServices
                    else -> emptySet()
                },
            )
        }

        val merge = A11yList.merge(input.enabledA11yRaw, input.guardedA11y)
        return PatrolOutput(
            phase = Phase.RUNNING,
            events = events,
            startComponents = starts,
            a11yValue = if (merge.changed) merge.value else null,
            a11yAdded = if (merge.changed) merge.added else emptyList(),
            memory = nextMemory,
            resetAdj = false,
        )
    }

    fun noteImmediateFailure(
        memory: Map<String, AppMemory>,
        app: GuardedApp,
        cap: Int = 3,
        reason: String = "",
    ): Pair<Map<String, AppMemory>, EventDraft?> {
        val prev = memory[app.packageName] ?: AppMemory()
        if (prev.gaveUp) return memory to null
        val failures = prev.failures + 1
        val gaveUp = failures >= cap
        val next = memory + (app.packageName to prev.copy(
            failures = failures,
            gaveUp = gaveUp,
            awaitingResult = false,
        ))
        val named = listOf(app.component).filter { it.isNotBlank() }
        return next to EventText.startFail(app.label, app.packageName, reason, named)
    }

    fun noteServiceFailure(
        memory: Map<String, AppMemory>,
        app: GuardedApp,
        cap: Int = 3,
        reason: String = "",
    ): Pair<Map<String, AppMemory>, EventDraft?> {
        val prev = memory[app.packageName] ?: AppMemory()
        val key = app.component
        if (key.isBlank() || prev.gaveUp) return memory to null
        val count = (prev.serviceFailures[key] ?: 0) + 1
        if (count > cap) return memory to null
        val next = memory + (app.packageName to prev.copy(
            serviceFailures = prev.serviceFailures + (key to count),
            awaitingServices = prev.awaitingServices - key,
        ))
        return next to EventText.startFail(app.label, app.packageName, reason, listOf(key))
    }

    fun armStart(previousComponent: String?, component: String): Boolean {
        return component.isNotBlank() && previousComponent != component
    }

    fun resetGiveUps(memory: Map<String, AppMemory>): Map<String, AppMemory> {
        return memory.mapValues { (_, item) ->
            item.copy(
                failures = 0,
                gaveUp = false,
                awaitingResult = false,
                forceRetry = true,
                serviceFailures = emptyMap(),
                awaitingServices = emptySet(),
            )
        }
    }

    fun retry(memory: Map<String, AppMemory>, packageName: String): Map<String, AppMemory> {
        val current = memory[packageName] ?: AppMemory()
        return memory + (packageName to current.copy(
            failures = 0,
            gaveUp = false,
            awaitingResult = false,
            forceRetry = true,
            serviceFailures = emptyMap(),
            awaitingServices = emptySet(),
        ))
    }
}
