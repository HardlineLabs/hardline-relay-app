package com.hardlinelabs.relay.core

/** Continuous listening with infrequent announcements. Times are monotonic while running. */
class NodeDiscovery(val id: String, knownNodes: Set<Int>, firstRequestAt: Long) {
    companion object {
        const val INTERVAL_MS = 600_000L
    }

    private val known = knownNodes.toSet()
    var nextRequestAt = firstRequestAt
        private set

    var requests = 0
        private set

    var stopped: String? = null
        private set

    val finished
        get() = stopped != null

    fun isNew(number: Int) = number !in known

    fun ready(now: Long, busy: Boolean) = !finished && !busy && now >= nextRequestAt

    fun submitted(now: Long) {
        check(!finished && now >= nextRequestAt)
        requests++
        nextRequestAt = now + INTERVAL_MS
    }

    fun stop(reason: String) {
        stopped = reason
    }
}
