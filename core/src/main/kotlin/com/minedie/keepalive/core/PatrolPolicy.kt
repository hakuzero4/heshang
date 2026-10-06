package com.minedie.keepalive.core

data class PatrolInput(
    val masterEnabled: Boolean,
    val bootReady: Boolean,
    val daemonAlive: Boolean,
    val daemonStartedBefore: Boolean,
    val apps: List<GuardedApp>,
    val alivePackages: Set<String>,
    val memory: Map<String, AppMemory>,
    val guardedA11y: Set<String>,
    val enabledA11yRaw: String?,
    val failureCap: Int = 3,
)

data class PatrolOutput(
    val phase: Phase,
    val events: List<EventDraft>,
    val startDaemon: Boolean,
    val markDaemonStarted: Boolean,
    val stopDaemon: Boolean,
    val startComponents: List<GuardedApp>,
    val a11yValue: String?,
    val a11yAdded: List<String>,
    val memory: Map<String, AppMemory>,
    val resetAdj: Boolean,
)

object PatrolPolicy {
    fun decide(input: PatrolInput): PatrolOutput {
        if (!input.masterEnabled) {
            return PatrolOutput(
                phase = Phase.PAUSED,
                events = emptyList(),
                startDaemon = false,
                markDaemonStarted = input.daemonStartedBefore,
                stopDaemon = input.daemonAlive,
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
                startDaemon = false,
                markDaemonStarted = input.daemonStartedBefore,
                stopDaemon = false,
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
        val startDaemon = !input.daemonAlive
        if (startDaemon) {
            events += if (input.daemonStartedBefore) EventText.pulled() else EventText.daemonStarted()
        }

        for (app in input.apps) {
            if (app.packageName.isBlank() || !app.enabled) continue
            val prev = input.memory[app.packageName] ?: AppMemory()
            val alive = app.packageName in input.alivePackages
            var failures = prev.failures
            var gaveUp = prev.gaveUp

            if (prev.awaitingResult && alive) {
                events += EventText.startOk(app.label, app.packageName)
                failures = 0
                gaveUp = false
            } else if (prev.awaitingResult && !alive) {
                failures += 1
                gaveUp = failures >= input.failureCap
                events += EventText.startFail(app.label, app.packageName)
            }
            if (alive && !prev.awaitingResult) {
                failures = 0
                gaveUp = false
            }

            val dropped = prev.wasAlive == true && !alive
            if (dropped) {
                events += EventText.lost(app.label, app.packageName)
            }
            val wantRetry = !alive &&
                !gaveUp &&
                failures < input.failureCap &&
                app.component.isNotBlank() &&
                (dropped || prev.awaitingResult || prev.failures > 0 || prev.forceRetry)
            if (wantRetry) {
                starts += app
            }
            nextMemory[app.packageName] = AppMemory(
                wasAlive = alive,
                failures = failures,
                gaveUp = gaveUp,
                awaitingResult = wantRetry,
            )
        }

        val merge = A11yList.merge(input.enabledA11yRaw, input.guardedA11y)
        return PatrolOutput(
            phase = if (input.daemonAlive) Phase.RUNNING else Phase.PULLING,
            events = events,
            startDaemon = startDaemon,
            markDaemonStarted = input.daemonStartedBefore || input.daemonAlive || startDaemon,
            stopDaemon = false,
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
        return next to EventText.startFail(app.label, app.packageName, reason)
    }

    fun armStart(previousComponent: String?, component: String): Boolean {
        return component.isNotBlank() && previousComponent != component
    }

    fun resetGiveUps(memory: Map<String, AppMemory>): Map<String, AppMemory> {
        return memory.mapValues { (_, item) ->
            item.copy(failures = 0, gaveUp = false, awaitingResult = false, forceRetry = true)
        }
    }

    fun retry(memory: Map<String, AppMemory>, packageName: String): Map<String, AppMemory> {
        val current = memory[packageName] ?: AppMemory()
        return memory + (packageName to current.copy(failures = 0, gaveUp = false, awaitingResult = false, forceRetry = true))
    }
}
