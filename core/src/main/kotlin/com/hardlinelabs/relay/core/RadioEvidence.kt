package com.hardlinelabs.relay.core

import kotlin.math.*

/** Metadata only. No packet bodies, keys, or provisioning packages belong in this model. */
data class RadioEvent(
    val time: Long,
    val direction: String,
    val kind: String,
    val source: String = "",
    val destination: String = "",
    val packetId: Int = 0,
    val channel: Int = -1,
    val size: Int = 0,
    val rssi: Int? = null,
    val snr: Float? = null,
    val hops: Int? = null,
    val hopLimit: Int? = null,
    val relay: Int? = null,
    val mqtt: Boolean = false,
    val distance: Double? = null,
    val positionTime: Long? = null,
    val context: String = "",
    val survey: String = "",
    val outcome: String = "Observed",
    val note: String = "",
    val elapsed: Long? = null,
    val transport: String = "Unknown",
)

data class RadioNode(
    val number: Int,
    val id: String,
    val name: String,
    val channel: Int,
    val lastKnown: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val positionTime: Long? = null,
    val observed: Long = 0,
    val acknowledged: Long = 0,
    val rssi: Int? = null,
    val snr: Float? = null,
    val hops: Int? = null,
)

object RadioEvidence {
    fun hops(start: Int, remaining: Int): Int? =
        if (start in 1..7 && remaining in 0..start) start - remaining else null

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = Math.toRadians(lat1)
        val b = Math.toRadians(lat2)
        val dLat = b - a
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(a) * cos(b) * sin(dLon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun validPosition(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() &&
        lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    fun counterDelta(previous: Long?, current: Long): Long? =
        previous?.takeIf { current >= it }?.let { current - it }

    fun ranked(nodes: List<RadioNode>, now: Long) = nodes.sortedWith(
        compareByDescending<RadioNode> { it.acknowledged > 0 && now - it.acknowledged in 0..600_000 }
            .thenByDescending { it.observed > 0 && now - it.observed in 0..600_000 }
            .thenByDescending { it.observed }.thenByDescending { it.lastKnown }
    )
}

/** One addressed request at a time. Deadlines use monotonic time, independent of phone clock edits. */
class RadioSurvey(val id: String, val targets: List<Int>, val started: Long, val attemptsPerNode: Int) {
    data class Attempt(val node: Int, val packetId: Int, val sent: Long,
                       var outcome: String = "Awaiting acknowledgment", var elapsed: Long? = null)
    val attempts = mutableListOf<Attempt>()
    var stopped: String? = null
        private set
    private var nextAt = started
    val budget get() = (targets.size * attemptsPerNode).coerceAtMost(12)
    val pending get() = attempts.lastOrNull()?.takeIf { it.outcome == "Awaiting acknowledgment" }
    val finished get() = stopped != null || (attempts.size >= budget && pending == null)

    init {
        require(targets.isNotEmpty() && targets.distinct().size == targets.size)
        require(targets.none { it == 0 || it == -1 })
        require(attemptsPerNode in 1..4)
    }

    fun next(now: Long, busyChannel: Boolean): Int? {
        if (finished) return null
        if (now - started >= 360_000) { stop("Survey time limit reached"); return null }
        pending?.let {
            if (now - it.sent >= 25_000) {
                it.outcome = "Unconfirmed"
                nextAt = now + 5_000
            }
            return null
        }
        if (busyChannel || now < nextAt || attempts.size >= budget) return null
        return targets[attempts.size % targets.size]
    }

    fun sent(node: Int, packetId: Int, now: Long) {
        check(!finished && pending == null && attempts.size < budget)
        require(node == targets[attempts.size % targets.size] && packetId != 0)
        attempts.add(Attempt(node, packetId, now))
        nextAt = now + 15_000
    }

    fun status(packetId: Int, outcome: String, now: Long): Boolean {
        if (stopped != null) return false
        val a = attempts.firstOrNull { it.packetId == packetId } ?: return false
        if (now - a.sent !in 0..180_000 || a.outcome == "Acknowledged") return false
        if (outcome !in setOf("Acknowledged", "Relay acknowledgment", "Failed")) return false
        a.outcome = outcome
        a.elapsed = now - a.sent
        nextAt = max(nextAt, now + 5_000)
        return true
    }

    fun stop(reason: String) {
        pending?.outcome = "Unconfirmed"
        stopped = reason
    }

    fun brief(): String {
        val confirmed = attempts.count { it.outcome == "Acknowledged" }
        val relayed = attempts.count { it.outcome == "Relay acknowledgment" }
        return "$confirmed destination acknowledgments, $relayed routing acknowledgments from ${attempts.size} checks. " +
            if (confirmed == 0) "Destination confirmation was not exposed. Silence does not prove a node is offline."
            else "Meshtastic acknowledgment evidence; not a complete route or a guarantee of future delivery."
    }
}
