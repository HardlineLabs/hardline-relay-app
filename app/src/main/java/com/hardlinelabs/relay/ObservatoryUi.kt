package com.hardlinelabs.relay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hardlinelabs.relay.core.*
import java.util.Locale

/** Page containers, scroll positions and inspectors survive live telemetry refreshes. */
class ObservatoryUi(private val activity: Activity, private val atak: View,
                    private val startCapture: () -> Unit, private val export: (List<RadioEvent>) -> Unit,
                    private val usePhoneGps: () -> Unit,
                    private val stateSource: (() -> MonitorState)? = null,
                    private val shutdownRadio: (Int) -> Unit = { RadioMonitorService.instance?.shutdownRadio(it) }) : LinearLayout(activity) {
    private val ui = RadioUi(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val body = FrameLayout(activity)
    private val heading: TextView
    private val connection: TextView
    private val testBanner: Button
    private var testNode: RadioNode? = null
    private val navButtons = mutableListOf<Button>()
    private val pages = mutableMapOf<Int, View>()
    private val radioBindings = mutableListOf<(MonitorState) -> Unit>()
    private var page = 0
    private var lastState = stateSource?.invoke() ?: retainedState(activity)
    private var feed: PacketFeed? = null
    private var map: SatelliteMap? = null
    private var meshPage: MeshPage? = null
    private var inspector: NodeInspector? = null
    private var foreground = false
    private var displayedRadio = 0
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 1000) } }
    init {
        orientation = VERTICAL; setBackgroundColor(ui.bg)
        val header = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }; addView(header)
        ui.label(header, "HARDLINE  /  RELAY", 10f, ui.mint).letterSpacing = .16f
        heading = ui.label(header, "Radio", 25f).apply { typeface = Typeface.DEFAULT_BOLD }
        connection = ui.label(header, size = 12f, color = ui.muted).also(ui::single)
        testBanner = ui.button(header, "") { if (state().activeDiscovery.isNotEmpty()) { showPage(2); map?.showDiscovery() } else testNode?.let(::inspect) }.apply {
            tag = "test-progress"; visibility = GONE; textSize = 13f; isAllCaps = false
            maxLines = 2; minLines = 2; setTextColor(ui.mint)
        }
        addView(body, LayoutParams(-1, 0, 1f))
        val navigation = ui.row().apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6)) }
        listOf("Radio", "Mesh", "Map", "Activity", "HARDLINE\nATAK").forEachIndexed { index, title ->
            navButtons.add(ui.button(navigation, title) { showPage(index) }.apply { tag = "tab-$index"; if (index == 4) textSize = 9f })
        }
        addView(navigation); showPage(0)
    }
    fun connectionSnapshot(details: MonitorState) {
        if (RadioMonitorService.instance != null) return
        if (!details.connected) { lastState = lastState.copy(connected = false, message = details.message); refresh(); return }
        if (details.local != 0 && lastState.local != details.local) lastState = retainedState(activity, details.local)
        lastState = lastState.copy(connected = details.connected, local = details.local.takeIf { it != 0 } ?: lastState.local,
            shortName = details.shortName, longName = details.longName, battery = details.battery, voltage = details.voltage,
            utilization = details.utilization, airtime = details.airtime, metricTime = details.metricTime, uptime = details.uptime,
            context = details.context, message = details.message)
        refresh()
    }
    fun resume() { foreground = true; handler.removeCallbacks(tick); handler.post(tick); if (page == 2) map?.resumeMap() }
    fun pause() { foreground = false; handler.removeCallbacks(tick); map?.pauseMap() }
    fun destroy() { pause(); map?.destroy() }
    fun showAtak() = showPage(4)
    fun back(): Boolean { if (inspector == null) return false; closeInspector(); return true }
    private fun state(): MonitorState = stateSource?.invoke() ?: RadioMonitorService.instance?.state?.also { lastState = it }
        ?: lastState.copy(surveying = false, activeSurvey = "", activeDiscovery = "")
    private fun capturing() = stateSource != null || RadioMonitorService.instance != null
    private fun showPage(index: Int) {
        closeInspector(); pages[page]?.visibility = GONE
        if (page == 2) map?.pauseMap()
        page = index
        val view = pages.getOrPut(index) {
            when (index) {
                0 -> createRadioPage()
                1 -> MeshPage().also { meshPage = it }
                2 -> SatelliteMap(activity, ::inspect, usePhoneGps).also { map = it }
                3 -> PacketFeed(activity, ::inspect, ::activityTools).also { feed = it }
                else -> atak
            }.also { body.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        }
        view.visibility = VISIBLE
        heading.update(listOf("Radio", "Mesh", "Map", "Activity", "HARDLINE ATAK")[page])
        navButtons.forEachIndexed { i, b -> b.setTextColor(if (i == page) ui.mint else ui.muted) }
        if (page == 2 && foreground) map?.resumeMap()
        refresh()
    }
    private fun refresh() {
        val s = state()
        if (displayedRadio != s.local) { closeInspector(); feed?.clearView(); displayedRadio = s.local }
        connection.update(if (!capturing()) "Capture stopped · retained history" else s.message)
        updateTestBanner(s)
        when (page) { 0 -> radioBindings.forEach { it(s) }; 1 -> meshPage?.update(s); 2 -> map?.update(s); 3 -> feed?.update(s) }
        inspector?.update(if (page == 2) map?.inspectionState() ?: s else s)
        navButtons.first().apply {
            setTextColor(if (s.connected) ui.mint else android.graphics.Color.rgb(255, 160, 150))
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (s.connected)
                android.graphics.Color.rgb(24, 75, 61) else android.graphics.Color.rgb(100, 47, 50))
        }
    }
    private fun updateTestBanner(s: MonitorState) {
        val completed = s.surveyCompletedAt > 0 && android.os.SystemClock.elapsedRealtime() - s.surveyCompletedAt in 0..30_000
        val target = s.attempts.lastOrNull()?.node ?: s.surveyTargets.firstOrNull()
        testNode = s.nodes.firstOrNull { it.number == target }
        testBanner.visibility = if (s.activeDiscovery.isNotEmpty() || ((s.surveying || completed) && testNode != null)) VISIBLE else GONE
        testBanner.update(when {
            s.activeDiscovery.isNotEmpty() -> "Node discovery running · View map / Stop"
            !s.surveying -> "${if (s.surveyStopReason != null) "Test ended" else "Test complete"} · ${testNode?.name}\nView results"
            s.surveyTargets.size > 1 -> "Mesh survey · ${testNode?.name}\nCheck ${s.attempts.size.coerceAtLeast(1)}/${s.surveyBudget} · View test"
            else -> "Test in progress · ${testNode?.name}\nView test"
        })
    }
    private fun inspect(n: RadioNode) {
        closeInspector()
        inspector = NodeInspector(activity, n.number, ::closeInspector,
            { RadioMonitorService.instance?.startSurvey(it) }, { RadioMonitorService.instance?.stopSurvey() },
            { selected -> showPage(2); map?.focus(selected.id) }).also {
            // The underlying page stays attached, preserving map camera and list position.
            pages[page]?.visibility = INVISIBLE
            body.addView(it, FrameLayout.LayoutParams(-1, -1)); it.update(if (page == 2) map?.inspectionState() ?: state() else state())
        }
    }
    private fun closeInspector() { inspector?.let { body.removeView(it) }; inspector = null; pages[page]?.visibility = VISIBLE }
    private fun explain(title: String, text: String) = AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("Done", null).show()
    private fun live(parent: LinearLayout, size: Float = 14f, color: Int = ui.ink, value: (MonitorState) -> String): TextView {
        val label = ui.label(parent, size = size, color = color); radioBindings.add { label.update(value(it)) }; return label
    }
    private fun recent(s: MonitorState) = s.events.filter { it.time >= s.started && it.context == s.context && System.currentTimeMillis() - it.time in 0..600_000 }
    private fun createRadioPage(): View {
        val root = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16)) }
        val hero = ui.card(root, "YOUR CONNECTION")
        live(hero, 23f) { s -> when {
            !capturing() -> "Open a window into your radio."
            !s.connected -> "Waiting for your radio."
            s.surveying -> "Checking the mesh."
            s.attempts.any { it.outcome == "Acknowledged" } -> "Destination acknowledgment observed."
            s.attempts.any { it.outcome == "Relay acknowledgment" } -> "Routing activity confirmed."
            s.attempts.isNotEmpty() -> "Checks remain unconfirmed."
            recent(s).any { it.direction == "RX" && it.transport == "LoRa" } -> "Your radio is hearing mesh traffic."
            else -> "Connected. Listening for traffic."
        } }.apply { typeface = Typeface.DEFAULT_BOLD; minLines = 2 }
        live(hero, 13f, ui.muted) { s -> "${recent(s).filter { it.direction == "RX" && it.transport == "LoRa" }.map { it.source }.distinct().size} origins heard over RF in the last 10 minutes." }
        val action = ui.button(hero, "Start capture") {
            val s = state()
            if (!capturing()) startCapture() else if (s.surveying) RadioMonitorService.instance?.stopSurvey() else RadioMonitorService.instance?.startSurvey()
        }.apply { tag = "radio-check" }
        radioBindings.add { s -> action.update(if (!capturing()) "Start capture" else if (s.activeDiscovery.isNotEmpty()) "Stop discovery" else if (s.surveying) "Stop survey" else "Check my connection"); action.isEnabled = !capturing() || s.connected }
        live(hero, 13f, ui.muted) { it.surveyText }
        ui.label(hero, "Checks up to 4 eligible known nodes ranked by recent evidence, twice each (8 checks maximum). Alternates an addressed acknowledgment probe with a device-telemetry request. Waits between checks; no public chat. Inspect one node for a focused 4-check test.", 12f, ui.muted)
        ui.button(hero, "Why this assessment?") {
            explain("Reading the evidence", "A destination acknowledgment and a routing acknowledgment are different evidence. A routing acknowledgment may only mean that a relay repeated a packet. The pinned API cannot reliably identify destination acknowledgments for non-chat diagnostics.\n\nActual LoRa receptions independently show which origins were heard. A timeout means unconfirmed; it does not prove that a node is offline. Inspect a node to test it and watch each check.")
        }
        val traffic = ui.card(root, "PACKET ACTIVITY · 10 MIN")
        val chart = TrafficChart(activity); traffic.addView(chart, LayoutParams(-1, ui.dp(90)))
        radioBindings.add { chart.update(recent(it)) }
        live(traffic, 13f) { s -> val e = recent(s); "${e.count { it.direction == "RX" }} RX · ${e.count { it.direction == "TX" }} TX submissions" }
        val congestion = ui.card(root, "CHANNEL CONGESTION")
        val congestionChart = CongestionChart(activity); congestion.addView(congestionChart)
        radioBindings.add { congestionChart.update(it.local, it.congestion) }
        val health = ui.card(root, "RADIO-REPORTED CONDITION")
        live(health, 16f) { "Power  ${when (it.battery) { null, 0 -> "Unknown"; 101 -> "External power"; else -> "${it.battery}%" }}" }
        live(health, 16f) { "Channel busy  ${it.utilization?.let { v -> "%.1f%%".format(Locale.US, v) } ?: "Unavailable"}" }
        live(health, 16f) { "TX airtime  ${it.airtime?.let { v -> "%.1f%%".format(Locale.US, v) } ?: "Unavailable"}" }
        live(health, 16f) { "Uptime  ${it.uptime?.let { v -> "${v / 3600}h ${(v % 3600) / 60}m" } ?: "Unavailable"}" }
        live(health, 12f, ui.muted) { "Cached metrics · ${age(it.metricTime)}" }
        val power = ui.card(root, "POWER METRICS")
        live(power, 16f) { "Battery ${when (it.battery) { null, 0 -> "Unavailable"; 101 -> "External power"; else -> "${it.battery}%" }} · ${it.voltage?.let { v -> "%.2f V".format(Locale.US, v) } ?: "Voltage unavailable"}" }
        live(power, 12f, ui.muted) { "Radio-reported reading · ${age(it.metricTime)}" }
        ui.label(power, "Battery broadcast setting: unavailable through Meshtastic 2.7.13's external API. Device telemetry shares battery level; the separate power-sensor setting is not required.", 12f, ui.muted)
        ui.button(power, "Enable battery sharing in Meshtastic") {
            AlertDialog.Builder(activity).setTitle("Enable battery broadcasts")
                .setMessage("In Meshtastic, open Radio configuration > Telemetry, enable Device metrics, then save. Relay cannot read this setting through the pinned API. Your current telemetry interval and other sensor settings can be preserved there.")
                .setNegativeButton("Cancel", null).setPositiveButton("Open Meshtastic") { _, _ ->
                    activity.packageManager.getLaunchIntentForPackage("com.geeksville.mesh")?.let(activity::startActivity)
                }.show()
        }
        val setup = ui.card(root, "CURRENT RADIO")
        live(setup, 18f) { s -> s.nodes.firstOrNull { it.number == s.local }?.name ?: "No radio selected" }
        live(setup, 13f) { "Short name: ${it.shortName.ifBlank { "Unavailable" }} · ${it.local.toUInt().toString(16).padStart(8, '0')}" }
        live(setup, 13f) { "Long name: ${it.longName.ifBlank { "Unavailable" }}" }
        live(setup, 13f, ui.muted) { it.context }
        val shutdown = ui.button(setup, "Shutdown radio") {
            val expected = state()
            AlertDialog.Builder(activity).setTitle("Shutdown ${expected.longName.ifBlank { "radio" }}?")
                .setMessage("Shuts down the connected radio. You must turn it back on physically to reconnect.")
                .setNegativeButton("Cancel", null).setPositiveButton("Confirm shutdown") { _, _ ->
                    shutdownRadio(expected.local)
                }.show()
        }.apply { tag = "radio-shutdown" }
        radioBindings.add { shutdown.isEnabled = it.connected }
        ui.button(setup, "Refresh radio details") { RadioMonitorService.instance?.refreshRadio() }
        ui.button(setup, "Capture coverage", ::coverage)
        ui.button(setup, "Stop capture") { activity.stopService(Intent(activity, RadioMonitorService::class.java)); refresh() }
        ui.button(setup, "Open Meshtastic") { activity.packageManager.getLaunchIntentForPackage("com.geeksville.mesh")?.let(activity::startActivity) }
        return ScrollView(activity).apply { tag = "radio-scroll"; isFillViewport = true; addView(root) }
    }
    private inner class MeshPage : LinearLayout(activity) {
        private val count: TextView
        private val search: EditText
        private val list = ui.list().apply { tag = "mesh-list" }
        private var nodes = emptyList<RadioNode>()
        private var query = ""
        private var s = MonitorState()
        private val adapter = object : BaseAdapter() {
            override fun getCount() = nodes.size
            override fun getItem(position: Int) = nodes[position]
            override fun getItemId(position: Int) = nodes[position].number.toLong()
            override fun hasStableIds() = true
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val root = convertView as? LinearLayout ?: ui.column().apply {
                    val c = ui.card(this); ui.label(c, size = 19f).also(ui::single); ui.label(c, size = 13f, color = ui.mint).also(ui::single)
                    ui.label(c, size = 12f, color = ui.muted).also(ui::single)
                }
                bind(root, nodes[position]); return root
            }
        }
        init {
            orientation = VERTICAL
            val header = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }; addView(header)
            count = ui.label(header, size = 13f, color = ui.muted)
            search = EditText(activity).apply { hint = "Find a node by name or ID"; isSingleLine = true; textSize = 15f; setTextColor(ui.ink); setHintTextColor(ui.muted) }; header.addView(search)
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { query = text.toString(); update(s, true) }
                override fun afterTextChanged(text: Editable?) = Unit
            })
            ui.button(header, "Rank by recent evidence") { update(s, true); list.setSelection(0) }
            addView(list, LayoutParams(-1, 0, 1f)); list.adapter = adapter
            list.setOnItemClickListener { _, _, position, _ -> inspect(nodes[position]) }
        }
        private fun bind(root: LinearLayout, n: RadioNode) {
            val c = root.getChildAt(0) as LinearLayout
            (c.getChildAt(0) as TextView).update(n.name)
            (c.getChildAt(1) as TextView).update(nodeEvidence(n))
            (c.getChildAt(2) as TextView).update("${n.id} · ${if (n.latitude == null) "No location" else "Reported location"} · Tap to inspect")
        }
        fun update(state: MonitorState, rerank: Boolean = false) {
            s = state
            val ranked = RadioEvidence.ranked(s.nodes.filter { it.number != s.local && (it.name.contains(query, true) || it.id.contains(query, true)) }, System.currentTimeMillis())
            val byId = ranked.associateBy { it.number }
            val previous = nodes.map { it.number }.toSet()
            val next = if (rerank) ranked else nodes.mapNotNull { byId[it.number] } + ranked.filter { it.number !in previous }
            val structureChanged = nodes.map { it.number } != next.map { it.number }
            nodes = next
            count.update("${nodes.size} known ${if (nodes.size == 1) "node" else "nodes"} · cached and observed evidence")
            if (structureChanged) adapter.notifyDataSetChanged()
            else for (i in 0 until list.childCount) nodes.getOrNull(list.firstVisiblePosition + i)?.let { bind(list.getChildAt(i) as LinearLayout, it) }
        }
    }
    private fun activityTools() {
        val s = state()
        AlertDialog.Builder(activity).setTitle("Packet activity tools").setItems(arrayOf("Traffic insights", "Mark antenna / location change", "Export metadata", "Capture coverage", "Clear local history")) { _, i ->
            when (i) {
                0 -> {
                    val rx = s.events.filter { it.direction == "RX" && it.time >= s.started && it.context == s.context }
                    val talkers = rx.groupBy { it.source }.entries.sortedByDescending { it.value.size }.take(8).joinToString("\n") { "${nodeName(it.key, s)} · ${it.value.size} packets" }
                    val types = rx.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }.joinToString("\n") { "${it.key} · ${it.value}" }
                    val marker = s.events.lastOrNull { it.kind == "Comparison marker" }?.time
                    val comparison = marker?.let { t ->
                        val span = (System.currentTimeMillis() - t).coerceIn(1, 600_000)
                        "\n\nAROUND YOUR MARKER · equal ${span / 1000}s windows\n${rx.count { it.time in (t - span) until t }} RX before · ${rx.count { it.time in t..(t + span) }} RX after. Different traffic loads can affect this comparison."
                    }.orEmpty()
                    explain("This capture / radio context", "MOST OBSERVED\n${talkers.ifEmpty { "Waiting for packets." }}\n\nPACKET TYPES\n$types$comparison")
                }
                1 -> RadioMonitorService.instance?.markComparison()
                2 -> export(s.events)
                3 -> coverage()
                4 -> AlertDialog.Builder(activity).setTitle("Clear packet metadata?").setMessage("Deletes local activity history. Saved channel profiles remain available.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        RadioMonitorService.instance?.clearHistory() ?: ObservationStore(activity).use { it.radio = lastState.local; it.clear() }
                        feed?.clearView(); lastState = lastState.copy(events = emptyList()); refresh()
                    }.show()
            }
        }.show()
    }
    private fun coverage() = explain("Capture coverage", "Relay records received packet events exposed by Meshtastic and its own diagnostic submissions. TX is an API submission, not proof of an RF transmission. Activity shows only TX/RX; associated statuses update TX cards and appear in their expanded history.\n\nThe API does not expose every forwarded packet, retry, corrupt reception or undecryptable packet. Other apps' outbound submissions are incomplete. Encryption and rebroadcast flags are not exposed reliably and are omitted. Hops counts observed relays, not the configured maximum.\n\nRSSI/SNR describes the final reception. Payload size excludes RF headers. Positions are reported coordinates, not verified GPS fixes or RF distance estimates. Radio condition is cached telemetry with its age shown.\n\nMap imagery requires internet and has detailed U.S. coverage. Only requested map tile coordinates reach USGS; node metadata stays on the phone. The exported Meshtastic broadcast API is not sender-authenticated. No survey starts automatically.")
}

/** Opening the app offline restores only the last identified radio, never the old mixed history. */
private fun retainedState(context: android.content.Context, radio: Int? = null): MonitorState = ObservationStore(context).use { store ->
    store.radio = radio ?: store.latestRadio()
    val saved = store.snapshot()
    val records = saved?.optJSONArray("surveys")
    val details = saved?.optJSONObject("details") ?: org.json.JSONObject()
    MonitorState(message = "Capture stopped · retained radio data", local = store.radio, congestion = store.congestion(),
        shortName = details.optString("shortName"), longName = details.optString("longName"), context = details.optString("context"),
        battery = if (details.has("battery")) details.getInt("battery") else null,
        voltage = if (details.has("voltage")) details.getDouble("voltage").toFloat() else null,
        utilization = if (details.has("utilization")) details.getDouble("utilization").toFloat() else null,
        airtime = if (details.has("airtime")) details.getDouble("airtime").toFloat() else null,
        uptime = if (details.has("uptime")) details.getInt("uptime") else null, metricTime = details.optLong("metricTime"),
        nodes = readNodes(saved?.optJSONArray("nodes")), events = if (store.radio != 0) store.read() else emptyList(),
        surveys = records?.let { a -> (0 until a.length()).mapNotNull {
            runCatching { SurveyRecord.read(a.getJSONObject(it)) }.getOrNull()?.let { r ->
                if (r.ended == 0L) r.copy(ended = System.currentTimeMillis(), result = "Capture interrupted; survey not resumed") else r
            }
        } }.orEmpty())
}
