package com.minedie.keepalive.core

data class StatusInput(
    val moduleActive: Boolean,
    val masterEnabled: Boolean,
    val phase: Phase,
    val snapshotAgeMs: Long?,
    val intervalMs: Long,
    val daemonPid: Int,
    val watchdogPullsLastHour: Int,
    val awaitingHeartbeat: Boolean = false,
)

data class StatusView(
    val title: String,
    val subtitle: String,
    val healthy: Boolean,
    val warning: String?,
)

object StatusMachine {
    const val PULL_WARNING = "守护进程反复被系统回收"
    const val PULL_WARNING_LIMIT = 5
    const val HEARTBEAT_FLOOR_MS = 90_000L

    fun derive(input: StatusInput): StatusView {
        val warning = if (input.watchdogPullsLastHour > PULL_WARNING_LIMIT) PULL_WARNING else null
        if (!input.moduleActive) {
            return StatusView(
                title = "模块未激活",
                subtitle = "在 LSPosed 勾选系统框架和本模块",
                healthy = false,
                warning = warning,
            )
        }
        if (!input.masterEnabled) {
            return StatusView(
                title = "守护已暂停",
                subtitle = "关闭后所有守护暂停，配置不会丢失",
                healthy = false,
                warning = null,
            )
        }
        val age = input.snapshotAgeMs
        if (age == null) {
            return StatusView(
                title = "看门狗未就绪",
                subtitle = "系统进程里还没有看门狗。勾选系统框架后需要重启一次手机",
                healthy = false,
                warning = warning,
            )
        }
        // ColorOS freezes this app during games, so a short gap is not a dead watchdog.
        val limit = maxOf(input.intervalMs * 2, HEARTBEAT_FLOOR_MS)
        if (input.intervalMs > 0 && age > limit && !input.awaitingHeartbeat) {
            return StatusView(
                title = "看门狗异常",
                subtitle = "心跳超时",
                healthy = false,
                warning = warning,
            )
        }
        if (input.phase == Phase.WAITING_BOOT) {
            return StatusView(
                title = "等待开机延迟",
                subtitle = "延迟结束后开始巡检",
                healthy = false,
                warning = warning,
            )
        }
        if (input.daemonPid <= 0 && input.watchdogPullsLastHour > PULL_WARNING_LIMIT) {
            return StatusView(
                title = "看门狗正在拉起守护进程",
                subtitle = "守护进程暂时不在",
                healthy = false,
                warning = warning,
            )
        }
        val subtitle = if (input.daemonPid > 0) {
            "看门狗与守护进程均正常 · pid ${input.daemonPid}"
        } else {
            "巡检正常"
        }
        return StatusView(
            title = "守护运行中",
            subtitle = subtitle,
            healthy = true,
            warning = warning,
        )
    }
}
