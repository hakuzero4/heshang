package com.minedie.keepalive.core

enum class EventType {
    DAEMON_STARTED,
    WATCHDOG_PULLED_DAEMON,
    PROCESS_LOST,
    SILENT_START_OK,
    SILENT_START_FAIL,
    A11Y_RESTORED,
    A11Y_RESTORE_FAIL,
    HOOK_FAIL,
    ADJ_APPLIED,
}

enum class Phase {
    PAUSED,
    WAITING_BOOT,
    RUNNING,
    PULLING,
}

enum class EventTone {
    ACTION,
    LOSS,
    FAILURE,
}

data class EventDraft(
    val type: EventType,
    val title: String,
    val detail: String,
    val packageName: String? = null,
)

data class GuardedApp(
    val packageName: String,
    val label: String,
    val component: String = "",
    val enabled: Boolean = true,
    val components: List<String> = emptyList(),
) {
    /** Selected services. The legacy `component` field is the first one, so an older hook still starts it. */
    fun targets(): List<String> {
        val listed = components.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (listed.isNotEmpty()) return listed
        val single = component.trim()
        return if (single.isBlank()) emptyList() else listOf(single)
    }
}

data class GuardTotals(val apps: Int, val services: Int)

fun guardTotals(apps: List<GuardedApp>): GuardTotals {
    val enabled = apps.filter { it.enabled && it.packageName.isNotBlank() }
    return GuardTotals(enabled.size, enabled.sumOf { it.targets().size })
}

data class AppMemory(
    val wasAlive: Boolean? = null,
    val failures: Int = 0,
    val gaveUp: Boolean = false,
    val awaitingResult: Boolean = false,
    val forceRetry: Boolean = false,
    val serviceSeen: Set<String> = emptySet(),
    val serviceFailures: Map<String, Int> = emptyMap(),
    val awaitingServices: Set<String> = emptySet(),
)

fun EventType.tone(): EventTone = when (this) {
    EventType.PROCESS_LOST -> EventTone.LOSS
    EventType.SILENT_START_FAIL, EventType.A11Y_RESTORE_FAIL, EventType.HOOK_FAIL -> EventTone.FAILURE
    else -> EventTone.ACTION
}

object EventText {
    fun daemonStarted() = EventDraft(EventType.DAEMON_STARTED, "守护进程", "已启动")

    fun pulled() = EventDraft(EventType.WATCHDOG_PULLED_DAEMON, "看门狗", "已拉起守护进程")

    fun lost(label: String, packageName: String, components: Collection<String> = emptyList()) = EventDraft(
        EventType.PROCESS_LOST,
        titled("检测到掉线", label, components),
        "进程消失",
        packageName,
    )

    fun startOk(label: String, packageName: String, components: Collection<String> = emptyList()) = EventDraft(
        EventType.SILENT_START_OK,
        titled("静默拉起", label, components),
        "已启动进程",
        packageName,
    )

    fun startFail(
        label: String,
        packageName: String,
        reason: String = "",
        components: Collection<String> = emptyList(),
    ) = EventDraft(
        EventType.SILENT_START_FAIL,
        titled("静默拉起失败", label, components),
        if (reason.isBlank()) "未能启动" else "未能启动 · $reason",
        packageName,
    )

    /** Old rows only stored the app name. The log still names the service being guarded. */
    fun shownTitle(type: String, title: String, packageName: String?, servicesByPackage: Map<String, List<String>>): String {
        if (type != EventType.PROCESS_LOST.name &&
            type != EventType.SILENT_START_OK.name &&
            type != EventType.SILENT_START_FAIL.name
        ) {
            return title
        }
        val names = servicesByPackage[packageName].orEmpty()
            .map { simpleComponent(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (names.isEmpty() || names.any { title.contains(it) }) return title
        return "$title · ${names.joinToString("、")}"
    }

    fun a11yRestored(component: String) = EventDraft(
        EventType.A11Y_RESTORED,
        "无障碍",
        "已写回 ${simpleComponent(component)}",
        component,
    )

    fun a11yFail(component: String) = EventDraft(
        EventType.A11Y_RESTORE_FAIL,
        "无障碍",
        "写回失败 ${simpleComponent(component)}",
        component,
    )

    /** Old rows stored the full class path. The list only has room for the simple name. */
    fun shortDetail(detail: String): String {
        val space = detail.indexOf(' ')
        if (space <= 0 || detail.length <= 24) return detail
        val rest = detail.substring(space + 1)
        if (!rest.contains('.')) return detail
        val simple = simpleComponent(rest)
        if (simple.length < 2 || simple == rest) return detail
        return detail.substring(0, space) + " " + simple
    }

    private fun titled(action: String, label: String, components: Collection<String>): String {
        val services = components.map { simpleComponent(it) }.filter { it.isNotBlank() }.distinct()
        return if (services.isEmpty()) "$action · $label" else "$action · $label · ${services.joinToString("、")}"
    }

    private fun simpleComponent(component: String): String {
        val cls = component.substringAfterLast('/')
        return cls.substringAfterLast('.').removePrefix(".").ifBlank { cls.ifBlank { component } }
    }

    fun hookFail(detail: String) = EventDraft(EventType.HOOK_FAIL, "钩子异常", detail)

    fun adjApplied(label: String, packageName: String) = EventDraft(
        EventType.ADJ_APPLIED,
        "已设置保活",
        label,
        packageName,
    )
}
