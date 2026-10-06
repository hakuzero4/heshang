package com.minedie.keepalive.core

data class LogRow(
    val id: Long,
    val createdAt: Long,
    val type: EventType,
)

object EventLog {
    fun idsToDrop(newestFirst: List<LogRow>, max: Int): List<Long> {
        if (max <= 0) return newestFirst.map { it.id }
        if (newestFirst.size <= max) return emptyList()
        return newestFirst.drop(max).map { it.id }
    }

    fun countSince(
        events: List<LogRow>,
        type: EventType,
        now: Long,
        windowMs: Long,
    ): Int = events.count { row ->
        row.type == type && row.createdAt <= now && now - row.createdAt <= windowMs
    }
}

object Ranges {
    fun intervalSec(value: Int): Int = snap(value, min = 5, max = 120, step = 5)

    fun bootDelaySec(value: Int): Int = snap(value, min = 0, max = 120, step = 5)

    fun retention(value: Int): Int = snap(value, min = 50, max = 2000, step = 50)

    private fun snap(value: Int, min: Int, max: Int, step: Int): Int {
        val clamped = value.coerceIn(min, max)
        val steps = (clamped - min) / step
        return (min + steps * step).coerceIn(min, max)
    }
}

fun isPackageAlive(processNames: Set<String>, packageName: String): Boolean {
    return processNames.any { name -> name == packageName || name.startsWith("$packageName:") }
}
