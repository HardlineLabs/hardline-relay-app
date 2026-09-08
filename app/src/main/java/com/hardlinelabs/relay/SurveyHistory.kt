package com.hardlinelabs.relay

import com.hardlinelabs.relay.core.RadioNode
import com.hardlinelabs.relay.core.RadioEvidence
import org.json.JSONArray
import org.json.JSONObject

/** A survey owns a frozen origin and only nodes heard during its observation window. */
data class SurveyRecord(val id: String, val started: Long, val ended: Long = 0,
                        val result: String = "Survey running", val context: String,
                        val origin: RadioNode? = null, val nodes: List<RadioNode> = emptyList(),
                        val tested: List<Int> = emptyList()) {
    fun rangeMeters(): Double? {
        val own = origin ?: return null
        val lat = own.latitude ?: return null
        val lon = own.longitude ?: return null
        return nodes.filter { it.observed > 0 && it.latitude != null && it.longitude != null }
            .maxOfOrNull { RadioEvidence.distance(lat, lon, it.latitude!!, it.longitude!!) }
    }
    fun json() = JSONObject().put("id", id).put("started", started).put("ended", ended)
        .put("result", result).put("context", context).put("origin", origin?.let(::encodeNode))
        .put("nodes", JSONArray(nodes.map(::encodeNode))).put("tested", JSONArray(tested))
    companion object {
        fun read(j: JSONObject) = SurveyRecord(j.getString("id"), j.getLong("started"), j.optLong("ended"),
            j.optString("result"), j.optString("context"), j.optJSONObject("origin")?.let(::decodeNode),
            readNodes(j.optJSONArray("nodes")), j.optJSONArray("tested")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty())
    }
}

internal fun encodeNode(n: RadioNode) = JSONObject().put("number", n.number).put("id", n.id).put("name", n.name)
    .put("channel", n.channel).put("lastKnown", n.lastKnown).put("latitude", n.latitude).put("longitude", n.longitude)
    .put("positionTime", n.positionTime).put("observed", n.observed).put("acknowledged", n.acknowledged)
    .put("rssi", n.rssi).put("snr", n.snr).put("hops", n.hops).put("precision", n.positionPrecision).put("source", n.positionSource)
internal fun decodeNode(j: JSONObject) = RadioNode(j.getInt("number"), j.getString("id"), j.getString("name"),
    j.getInt("channel"), j.optLong("lastKnown"), if (j.has("latitude")) j.getDouble("latitude") else null,
    if (j.has("longitude")) j.getDouble("longitude") else null, if (j.has("positionTime")) j.getLong("positionTime") else null,
    j.optLong("observed"), j.optLong("acknowledged"), if (j.has("rssi")) j.getInt("rssi") else null,
    if (j.has("snr")) j.getDouble("snr").toFloat() else null, if (j.has("hops")) j.getInt("hops") else null,
    if (j.has("precision")) j.getInt("precision") else null, j.optString("source", "Reported position"))
internal fun readNodes(a: JSONArray?): List<RadioNode> = a?.let {
    (0 until minOf(a.length(), 1000)).mapNotNull { runCatching { decodeNode(a.getJSONObject(it)) }.getOrNull() }
}.orEmpty()
