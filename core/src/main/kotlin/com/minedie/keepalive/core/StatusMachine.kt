package com.minedie.keepalive.core

data class StatusInput(
    val moduleActive: Boolean,
    val masterEnabled: Boolean,
    val phase: Phase,
    val snapshotAgeMs: Long?,
    val intervalMs: Long,
    val awaitingHeartbeat: Boolean = false,
)

data class StatusView(
    val title: String,
    val subtitle: String,
    val healthy: Boolean,
    val warning: String?,
)

object StatusMachine {
    const val HEARTBEAT_FLOOR_MS = 90_000L

    fun derive(input: StatusInput): StatusView {
        if (!input.moduleActive) {
            return StatusView(
                title = "模块未激活",
                subtitle = "在 LSPosed 勾选系统框架和本模块",
                healthy = false,
                warning = null,
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
                warning = null,
            )
        }
        // The UI writes a snapshot only while it is open and pulling. Ignore the gap
        // from when it was closed; a missing reply after it opens is a dead watchdog.
        val limit = maxOf(input.intervalMs * 2, HEARTBEAT_FLOOR_MS)
        if (input.intervalMs > 0 && age > limit && !input.awaitingHeartbeat) {
            return StatusView(
                title = "看门狗异常",
                subtitle = "心跳超时",
                healthy = false,
                warning = null,
            )
        }
        if (input.phase == Phase.WAITING_BOOT) {
            return StatusView(
                title = "等待开机延迟",
                subtitle = "延迟结束后开始巡检",
                healthy = false,
                warning = null,
            )
        }
        return StatusView(
            title = "守护运行中",
            subtitle = "巡检正常",
            healthy = true,
            warning = null,
        )
    }
}
