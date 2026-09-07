package com.hardlinelabs.relay

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hardlinelabs.relay.core.*
import java.text.SimpleDateFormat
import java.util.*

/** A single recycled list owns packet scrolling. New arrivals wait while a packet is being read. */
internal class PacketFeed(context: Context, private val inspect: (RadioNode) -> Unit,
                          tools: () -> Unit) : LinearLayout(context) {
    private val ui = RadioUi(context)
    private val count: TextView
    private val arrivals: Button
    private val list = ui.list().apply { tag = "packet-list" }
    private var state = MonitorState()
    private var filter = "All"
    private var node: String? = null
    private var rows = emptyList<RadioEvent>()
    private var latest = emptyList<RadioEvent>()
    private var eventSnapshot = emptyList<RadioEvent>()
    private var packets = emptyList<RadioEvent>()
    private var scrolling = false
    private val expanded = mutableSetOf<String>()
    private val ids = mutableMapOf<String, Long>()
    private var nextId = 1L
    private val adapter = PacketAdapter()
    init {
        orientation = VERTICAL
        val header = ui.column().apply { setPadding(ui.dp(12), 0, ui.dp(12), 0) }; addView(header)
        count = ui.label(header, size = 13f, color = ui.muted).also(ui::single)
        val controls = ui.row(); header.addView(controls)
        listOf("All", "TX", "RX").forEach { title -> ui.button(controls, title) { filter = title; node = null; expanded.clear(); refresh(true) } }
        ui.button(controls, "Tools", tools)
        arrivals = ui.button(header, "Live · newest packets first") { refresh(true); list.setSelection(0) }.apply { tag = "packet-arrivals" }
        addView(list, LayoutParams(-1, 0, 1f)); list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) { scrolling = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE }
            override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) = Unit
        })
    }
    fun follow(id: String) { node = id; filter = "All"; expanded.clear(); refresh(true); list.setSelection(0) }
    fun update(s: MonitorState) { state = s; refresh(false) }
    fun clearView() { expanded.clear(); rows = emptyList(); adapter.notifyDataSetChanged() }
    private fun key(e: RadioEvent) = "${e.time}/${e.direction}/${e.source}/${e.destination}/${e.packetId}/${e.kind}"
    private fun refresh(force: Boolean) {
        if (eventSnapshot != state.events) { eventSnapshot = state.events; packets = RadioEvidence.packets(eventSnapshot) }
        latest = packets.filter { (filter == "All" || it.direction == filter) &&
            (node == null || it.source == node || it.destination == node) }.reversed()
        val top = list.firstVisiblePosition == 0 && (list.getChildAt(0)?.top ?: list.paddingTop) >= list.paddingTop
        val oldKeys = rows.map(::key)
        val newKeys = latest.map(::key)
        val canInsert = force || (!scrolling && top && expanded.isEmpty())
        val byKey = latest.associateBy(::key)
        // Keep existing rows/anchor during gestures and expansion, including across history expiry.
        val updated = if (canInsert) latest else rows.map { byKey[key(it)] ?: it }
        rows = updated
        expanded.retainAll(rows.map(::key).toSet())
        if (oldKeys != rows.map(::key)) adapter.notifyDataSetChanged()
        else for (i in 0 until list.childCount) {
            val position = list.firstVisiblePosition + i
            if (position in rows.indices) (list.getChildAt(i).tag as? PacketViews)?.bind(rows[position])
        }
        val oldKeySet = oldKeys.toSet()
        val waiting = newKeys.count { it !in oldKeySet }
        arrivals.update(if (!canInsert && waiting > 0) "$waiting new packets · tap to show" else "Live · ${node?.let { nodeName(it, state) } ?: filter} · tap for newest")
        count.update("${packets.count { it.direction == "TX" }} TX · ${packets.count { it.direction == "RX" }} RX · ${if (latest.isEmpty()) "waiting for packets" else "tap a packet to expand"}")
        if (force) list.setSelection(0)
        if (ids.size > 6000) ids.keys.retainAll((oldKeys + newKeys).toSet())
    }
    private inner class PacketAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = ids.getOrPut(key(rows[position])) { nextId++ }
        override fun hasStableIds() = true
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val root = convertView as? LinearLayout ?: ui.column().apply { tag = PacketViews(this) }
            (root.tag as PacketViews).bind(rows[position]); return root
        }
    }
    private inner class PacketViews(root: LinearLayout) {
        val card = ui.card(root)
        val heading = ui.label(card, size = 12f)
        val endpoint = ui.label(card, size = 18f).apply { typeface = Typeface.DEFAULT_BOLD; ui.single(this) }
        val glance = ui.label(card, size = 13f, color = ui.muted)
        val details = ui.column().also { card.addView(it); it.visibility = GONE }
        val body = ui.label(details, size = 13f, color = ui.muted)
        private var event: RadioEvent? = null
        val inspectButton = ui.button(details, "Inspect node") {
            event?.let { e -> state.nodes.firstOrNull { it.id == if (e.direction == "TX") e.destination else e.source }?.let(inspect) }
        }
        init {
            card.isFocusable = true
            card.setOnClickListener {
                event?.let { e ->
                    if (!expanded.add(key(e))) expanded.remove(key(e))
                    bind(e)
                }
            }
        }
        fun bind(e: RadioEvent) {
            event = e
            val open = key(e) in expanded
            heading.setTextColor(if (e.direction == "RX") ui.mint else ui.blue)
            heading.update("${e.direction}  ·  ${clock(e.time)}  ·  ${if (open) "Collapse ▴" else "Details ▾"}")
            val id = if (e.direction == "TX") e.destination else e.source
            endpoint.update("${if (e.direction == "TX") "To" else "From"} ${nodeName(id, state)}")
            val status = if (e.direction == "RX") "Received" else e.outcome
            glance.update("$status · ${if (e.size < 0) "Size unknown" else "${e.size} B payload"}\n${hopsLabel(e)}${if (e.transport == "MQTT") " · MQTT" else ""}")
            if (open) {
                val transitions = state.events.filter { it.direction == "STATUS" && it.packetId == e.packetId && e.packetId != 0 &&
                    it.time - e.time in 0..180_000 && it.context == e.context && it.survey == e.survey &&
                    (it.destination.isEmpty() || it.destination == e.destination) }.takeIf { e.direction == "TX" }.orEmpty()
                body.update("${e.kind}\n${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(e.time))}\n" +
                    "From ${nodeName(e.source, state)} · ${e.source}\nTo ${nodeName(e.destination, state)} · ${e.destination}\n" +
                    "Packet ID ${e.packetId.toUInt()} · Channel ${e.channel.takeIf { it >= 0 } ?: "unknown"}\n" +
                    "RSSI ${e.rssi?.let { "$it dBm" } ?: "unavailable"} · SNR ${e.snr?.let { "$it dB" } ?: "unavailable"}\n" +
                    "Remaining hop allowance ${e.hopLimit ?: "unknown"}\n" +
                    "Last relay byte ${e.relay?.toString(16) ?: "unknown"} (partial ID)\n" +
                    "${distance(e.distance)} · position ${age(e.positionTime)}\nTransport ${e.transport}\n${e.context}\n\n" +
                    "Size is the exposed payload, not the complete RF packet. Hops counts observed relays; unknown fields stay unknown.\n${e.note}" +
                    if (transitions.isEmpty()) "" else "\n\nSTATUS HISTORY\n" + transitions.joinToString("\n") { "${clock(it.time)} · ${it.outcome}" })
                inspectButton.isEnabled = state.nodes.any { it.id == id }
            }
            details.visibility = if (open) VISIBLE else GONE
            card.contentDescription = "${e.direction}, ${endpoint.text}, ${glance.text}, ${if (open) "expanded" else "collapsed"}"
        }
    }
}
