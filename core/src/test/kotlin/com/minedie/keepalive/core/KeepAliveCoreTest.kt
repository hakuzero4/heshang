package com.minedie.keepalive.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveCoreTest {
    private val scene = GuardedApp("com.omarea.vtools", "Scene", "")
    private val sceneWithService = scene.copy(component = "com.omarea.vtools/.KeepService")

    @Test
    fun masterOffKeepsConfigAndEmitsNothing() {
        val out = PatrolPolicy.decide(
            base(master = false, daemonAlive = true, memory = mapOf(scene.packageName to AppMemory(wasAlive = true))),
        )
        assertEquals(Phase.PAUSED, out.phase)
        assertTrue(out.events.isEmpty())
        assertTrue(out.resetAdj)
        assertTrue(out.stopDaemon)
        assertTrue(out.memory.isEmpty())
        assertTrue(out.startComponents.isEmpty())
    }

    @Test
    fun bootDelayBlocksPatrol() {
        val memory = mapOf(scene.packageName to AppMemory(wasAlive = true))
        val out = PatrolPolicy.decide(base(bootReady = false, memory = memory, alive = emptySet()))
        assertEquals(Phase.WAITING_BOOT, out.phase)
        assertTrue(out.events.isEmpty())
        assertFalse(out.startDaemon)
        assertEquals(memory, out.memory)
    }

    @Test
    fun bootWaitKeepsTheArmedStartUntilTheDelayEnds() {
        val armed = mapOf(scene.packageName to AppMemory(forceRetry = true))
        val waiting = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = emptySet(), bootReady = false, memory = armed),
        )
        assertTrue(waiting.startComponents.isEmpty())
        assertTrue(waiting.memory.getValue(scene.packageName).forceRetry)

        val ready = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = emptySet(), memory = waiting.memory),
        )
        assertEquals(listOf(sceneWithService), ready.startComponents)
    }

    @Test
    fun firstSightOfDeadProcessIsNotADrop() {
        val out = PatrolPolicy.decide(base(alive = emptySet()))
        assertTrue(out.events.none { it.type == EventType.PROCESS_LOST })
        assertTrue(out.startComponents.isEmpty())
        assertEquals(false, out.memory.getValue(scene.packageName).wasAlive)
    }

    @Test
    fun dropWithoutComponentOnlyRecordsLoss() {
        var memory = PatrolPolicy.decide(base(alive = setOf(scene.packageName))).memory
        val out = PatrolPolicy.decide(base(alive = emptySet(), memory = memory))
        assertEquals(listOf(EventType.PROCESS_LOST), out.events.map { it.type })
        assertEquals("检测到掉线 · Scene", out.events.single().title)
        assertTrue(out.startComponents.isEmpty())
    }

    @Test
    fun dropWithServiceRequestsSilentStart() {
        val first = PatrolPolicy.decide(base(apps = listOf(sceneWithService), alive = setOf(scene.packageName)))
        val out = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = emptySet(), memory = first.memory),
        )
        assertEquals(EventType.PROCESS_LOST, out.events.single().type)
        assertEquals(listOf(sceneWithService), out.startComponents)
        assertTrue(out.memory.getValue(scene.packageName).awaitingResult)
    }

    @Test
    fun failedStartsStopAfterCapUntilReset() {
        var memory = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = setOf(scene.packageName)),
        ).memory
        memory = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = emptySet(), memory = memory),
        ).memory
        repeat(3) {
            val out = PatrolPolicy.decide(
                base(apps = listOf(sceneWithService), alive = emptySet(), memory = memory),
            )
            assertTrue(out.events.any { it.type == EventType.SILENT_START_FAIL })
            memory = out.memory
        }
        assertTrue(memory.getValue(scene.packageName).gaveUp)
        val stopped = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = emptySet(), memory = memory),
        )
        assertTrue(stopped.startComponents.isEmpty())
        assertTrue(stopped.events.none { it.type == EventType.SILENT_START_FAIL })

        val resumed = PatrolPolicy.decide(
            base(
                apps = listOf(sceneWithService),
                alive = emptySet(),
                memory = PatrolPolicy.resetGiveUps(memory),
            ),
        )
        assertEquals(listOf(sceneWithService.packageName), resumed.startComponents.map { it.packageName })
    }

    @Test
    fun processReturningAfterStartCountsAsSilentStart() {
        var memory = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = setOf(scene.packageName)),
        ).memory
        memory = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = emptySet(), memory = memory),
        ).memory
        val back = PatrolPolicy.decide(
            base(apps = listOf(sceneWithService), alive = setOf(scene.packageName), memory = memory),
        )
        assertEquals(listOf(EventType.SILENT_START_OK), back.events.map { it.type })
        assertEquals(0, back.memory.getValue(scene.packageName).failures)
    }

    @Test
    fun immediateFailureDoesNotWaitForNextPatrol() {
        val memory = mapOf(scene.packageName to AppMemory(wasAlive = false, awaitingResult = true))
        val (next, event) = PatrolPolicy.noteImmediateFailure(memory, sceneWithService)
        assertEquals(EventType.SILENT_START_FAIL, event?.type)
        assertFalse(next.getValue(scene.packageName).awaitingResult)
        assertEquals(1, next.getValue(scene.packageName).failures)
    }

    @Test
    fun daemonStartThenPull() {
        val first = PatrolPolicy.decide(base(daemonAlive = false, daemonStartedBefore = false))
        assertEquals(EventType.DAEMON_STARTED, first.events.single { it.type == EventType.DAEMON_STARTED }.type)
        assertTrue(first.startDaemon)
        assertEquals(Phase.PULLING, first.phase)

        val later = PatrolPolicy.decide(base(daemonAlive = false, daemonStartedBefore = true, alive = setOf(scene.packageName)))
        assertEquals(EventType.WATCHDOG_PULLED_DAEMON, later.events.single().type)
        assertEquals("已拉起守护进程", later.events.single().detail)
    }

    @Test
    fun a11yMergeKeepsUnguardedServices() {
        val merge = A11yList.merge(
            "pkg/a:pkg/b",
            setOf("pkg/b", "pkg/c"),
        )
        assertTrue(merge.changed)
        assertEquals("pkg/a:pkg/b:pkg/c", merge.value)
        assertEquals(listOf("pkg/c"), merge.added)
        val same = A11yList.merge(merge.value, setOf("pkg/c"))
        assertFalse(same.changed)
        assertTrue(same.added.isEmpty())
    }

    @Test
    fun statusFollowsTheOverviewTable() {
        val running = StatusMachine.derive(
            StatusInput(true, true, Phase.RUNNING, 1_000, 20_000, 14891, 0),
        )
        assertEquals("守护运行中", running.title)
        assertTrue(running.healthy)
        assertTrue(running.subtitle.contains("14891"))

        assertEquals(
            "模块未激活",
            StatusMachine.derive(StatusInput(false, true, Phase.RUNNING, 0, 20_000, 1, 0)).title,
        )
        assertEquals(
            "守护已暂停",
            StatusMachine.derive(StatusInput(true, false, Phase.RUNNING, 0, 20_000, 1, 0)).title,
        )
        assertEquals(
            "等待开机延迟",
            StatusMachine.derive(StatusInput(true, true, Phase.WAITING_BOOT, 0, 20_000, 0, 0)).title,
        )
        assertEquals(
            "看门狗未就绪",
            StatusMachine.derive(StatusInput(true, true, Phase.RUNNING, null, 20_000, 1, 0)).title,
        )
        assertEquals(
            "看门狗异常",
            StatusMachine.derive(StatusInput(true, true, Phase.RUNNING, 40_001, 20_000, 1, 0)).title,
        )
        assertEquals(
            "看门狗正在拉起守护进程",
            StatusMachine.derive(StatusInput(true, true, Phase.PULLING, 1_000, 20_000, 0, 0)).title,
        )
        val warned = StatusMachine.derive(StatusInput(true, true, Phase.RUNNING, 1_000, 20_000, 4, 6))
        assertEquals(StatusMachine.PULL_WARNING, warned.warning)
    }

    @Test
    fun logTrimKeepsNewestAndCountsSilentStartsInWindow() {
        val rows = (1L..5L).map { LogRow(it, it * 1_000, EventType.DAEMON_STARTED) }
        assertEquals(listOf(1L, 2L), EventLog.idsToDrop(rows.sortedByDescending { it.id }, 3).sorted())
        val now = 100_000L
        val events = listOf(
            LogRow(1, now - 1_000, EventType.SILENT_START_OK),
            LogRow(2, now - 86_400_000 - 1, EventType.SILENT_START_OK),
            LogRow(3, now - 1_000, EventType.SILENT_START_FAIL),
        )
        assertEquals(1, EventLog.countSince(events, EventType.SILENT_START_OK, now, 86_400_000))
    }

    @Test
    fun rangesSnapToTheSliderSteps() {
        assertEquals(20, Ranges.intervalSec(20))
        assertEquals(5, Ranges.intervalSec(1))
        assertEquals(120, Ranges.intervalSec(500))
        assertEquals(0, Ranges.bootDelaySec(0))
        assertEquals(300, Ranges.retention(300))
        assertEquals(50, Ranges.retention(10))
    }

    @Test
    fun longComponentDetailsCollapseToTheClassName() {
        val component = "io.enpass.app/.autofill.accessibilityautofill.EnpassAccessibilityService"
        assertEquals("已写回 EnpassAccessibilityService", EventText.a11yRestored(component).detail)
        assertEquals("写回失败 EnpassAccessibilityService", EventText.a11yFail(component).detail)
        assertEquals(
            "已写回 EnpassAccessibilityService",
            EventText.shortDetail("已写回 .autofill.accessibilityautofill.EnpassAccessibilityService"),
        )
        assertEquals("已拉起守护进程", EventText.shortDetail("已拉起守护进程"))
        assertEquals("microG 服务", EventText.shortDetail("microG 服务"))
    }

    @Test
    fun retryClearsOneGiveUpAndLeavesTheOther() {
        val other = AppMemory(wasAlive = false, failures = 3, gaveUp = true, awaitingResult = true)
        val memory = mapOf(
            scene.packageName to AppMemory(failures = 3, gaveUp = true, awaitingResult = true),
            "com.example.other" to other,
        )
        val next = PatrolPolicy.retry(memory, scene.packageName)
        val retried = next.getValue(scene.packageName)
        assertEquals(0, retried.failures)
        assertFalse(retried.gaveUp)
        assertFalse(retried.awaitingResult)
        assertTrue(retried.forceRetry)
        assertEquals(other, next.getValue("com.example.other"))
    }

    @Test
    fun choosingAServiceArmsADeadAppForSilentStart() {
        assertTrue(PatrolPolicy.armStart(null, "io.enpass.app/.sync.SyncService"))
        assertTrue(PatrolPolicy.armStart("", "io.enpass.app/.sync.SyncService"))
        assertFalse(PatrolPolicy.armStart("io.enpass.app/.sync.SyncService", "io.enpass.app/.sync.SyncService"))
        assertFalse(PatrolPolicy.armStart(null, ""))
        assertFalse(PatrolPolicy.armStart("io.enpass.app/.sync.SyncService", ""))
    }

    @Test
    fun startupAllowMatchesOnlyTheEnabledService() {
        val apps = listOf(
            GuardedApp("com.omarea.vtools", "Scene", "com.omarea.vtools/.services.KeepAliveService"),
            GuardedApp("app.revanced.android.gms", "microG", "app.revanced.android.gms/org.microg.gms.gcm.McsService", enabled = false),
            GuardedApp("net.dinglisch.android.taskerm", "Tasker", ""),
        )
        assertTrue(
            StartupAllow.matches(
                master = true,
                apps = apps,
                packageName = "com.omarea.vtools",
                className = "com.omarea.vtools.services.KeepAliveService",
            ),
        )
        assertFalse(
            StartupAllow.matches(
                master = false,
                apps = apps,
                packageName = "com.omarea.vtools",
                className = "com.omarea.vtools.services.KeepAliveService",
            ),
        )
        assertFalse(
            StartupAllow.matches(
                master = true,
                apps = apps,
                packageName = "app.revanced.android.gms",
                className = "org.microg.gms.gcm.McsService",
            ),
        )
        assertFalse(
            StartupAllow.matches(
                master = true,
                apps = apps,
                packageName = "net.dinglisch.android.taskerm",
                className = "net.dinglisch.android.taskerm.MonitorService",
            ),
        )
        assertFalse(
            StartupAllow.matches(
                master = true,
                apps = apps,
                packageName = "com.omarea.vtools",
                className = "com.omarea.vtools.services.BootService",
            ),
        )
    }

    @Test
    fun packageAliveMatchesSubprocessesOnly() {
        val names = setOf("com.example", "com.example:push", "com.example2")
        assertTrue(isPackageAlive(names, "com.example"))
        assertFalse(isPackageAlive(names, "com.other"))
        assertFalse(isPackageAlive(names, "com.ex"))
    }

    private fun base(
        master: Boolean = true,
        bootReady: Boolean = true,
        daemonAlive: Boolean = true,
        daemonStartedBefore: Boolean = true,
        apps: List<GuardedApp> = listOf(scene),
        alive: Set<String> = setOf(scene.packageName),
        memory: Map<String, AppMemory> = emptyMap(),
        guardedA11y: Set<String> = emptySet(),
        enabledA11y: String? = "",
    ) = PatrolInput(
        masterEnabled = master,
        bootReady = bootReady,
        daemonAlive = daemonAlive,
        daemonStartedBefore = daemonStartedBefore,
        apps = apps,
        alivePackages = alive,
        memory = memory,
        guardedA11y = guardedA11y,
        enabledA11yRaw = enabledA11y,
    )
}
