package com.minedie.keepalive.core

object A11yList {
    data class Merge(
        val changed: Boolean,
        val value: String,
        val added: List<String>,
    )

    fun parse(raw: String?): LinkedHashSet<String> {
        if (raw.isNullOrBlank()) return linkedSetOf()
        return raw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    /**
     * Puts guarded services back onto the enabled list.
     * Services the user did not guard stay as they are, including ones left disabled.
     */
    fun merge(raw: String?, guarded: Set<String>): Merge {
        val current = parse(raw)
        val added = guarded.filter { it.isNotBlank() && it !in current }.sorted()
        if (added.isEmpty()) {
            return Merge(changed = false, value = current.joinToString(":"), added = emptyList())
        }
        val next = LinkedHashSet(current)
        next.addAll(added)
        return Merge(changed = true, value = next.joinToString(":"), added = added)
    }
}
