package com.hardlinelabs.relay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.*
import com.hardlinelabs.relay.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Human summaries first; every assessment opens the observations behind it. */
class ObservatoryUi(private val activity: Activity, private val atak: View,
                    private val startCapture: () -> Unit, private val export: (List<RadioEvent>) -> Unit,
                    private val usePhoneGps: () -> Unit) : LinearLayout(activity) {
    private val ink = Color.rgb(237, 242, 243)
    private val muted = Color.rgb(153, 174, 185)
    private val mint = Color.rgb(93, 218, 196)
    private val blue = Color.rgb(116, 170, 255)
    private val amber = Color.rgb(245, 193, 108)
    private val bg = Color.rgb(13, 21, 27)
    private val handler = Handler(Looper.getMainLooper())
    private val body = FrameLayout(activity)
    private val heading = TextView(activity)
    private val connection = TextView(activity)
    private val navButtons = mutableListOf<Button>()
    private var page = 0
    private var filter = "All activity"
    private var nodeFilter: String? = null
    private var paused: List<RadioEvent>? = null
    private var rangeKm = 25f
    private var displayLimit = 60
    private var nodeLimit = 20
    private var lastState = MonitorState(message = "Capture is stopped", events = ObservationStore(activity).use { it.read() })
    private var baseline: Long? = null
    private var interactingUntil = 0L
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 3000) }
    }

    init {
        orientation = VERTICAL; setBackgroundColor(bg)
        val header = column().apply { setPadding(dp(20), dp(14), dp(20), dp(10)) }
        addView(header)
        label(header, "HARDLINE  /  RELAY", 11f, mint).apply { letterSpacing = .16f; typeface = Typeface.DEFAULT_BOLD }
        heading.apply { textSize = 30f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD }; header.addView(heading)
        connection.apply { textSize = 12f; setTextColor(muted); setPadding(0, dp(5), 0, 0) }; header.addView(connection)
        addView(body, LayoutParams(-1, 0, 1f))
        val navigation = LinearLayout(activity).apply { setPadding(dp(8), dp(4), dp(8), dp(8)); setBackgroundColor(Color.rgb(19, 30, 37)) }
        listOf("Radio", "Mesh", "Activity", "HARDLINE\nATAK").forEachIndexed { index, title ->
            val b = Button(activity).apply {
                text = title; isAllCaps = false; textSize = if (index == 3) 9f else 13f
                isSingleLine = false; setLines(2); ellipsize = null; setHorizontallyScrolling(false); minWidth = 0; minimumWidth = 0
                if (index == 3) setTextScaleX(.85f)
                setPadding(0, 0, 0, 0); setOnClickListener { page = index; render(false) }
            }
            navigation.addView(b, LayoutParams(0, dp(58), 1f)); navButtons.add(b)
        }
        addView(navigation)
        render(false)
    }

    fun resume() { handler.removeCallbacks(tick); handler.post(tick) }
    fun pause() { handler.removeCallbacks(tick) }
    fun showAtak() { page = 3; render(false) }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = VERTICAL }
    private fun label(parent: LinearLayout, value: String, size: Float = 14f, color: Int = ink) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4))
        parent.addView(this)
    }
    private fun card(parent: LinearLayout, title: String? = null): LinearLayout = column().apply {
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = GradientDrawable().apply { setColor(Color.rgb(24, 36, 44)); cornerRadius = dp(16).toFloat() }
        parent.addView(this, LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        if (title != null) label(this, title, 12f, mint).apply { letterSpacing = .08f; typeface = Typeface.DEFAULT_BOLD }
    }
    private fun button(parent: LinearLayout, text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text; isAllCaps = false; textSize = 14f; minHeight = dp(48)
        setTextColor(ink); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(43, 64, 74))
        setOnClickListener { action() }; parent.addView(this, LayoutParams(-1, -2))
    }
    private fun explain(title: String, body: String) = AlertDialog.Builder(activity).setTitle(title).setMessage(body).setPositiveButton("Done", null).show()
    private fun state(): MonitorState = RadioMonitorService.instance?.state?.also { lastState = it } ?: lastState.copy(connected = false, surveying = false)
    private fun name(id: String, s: MonitorState) = when (id) {
        "^all", "!ffffffff" -> "Broadcast"
        "^local" -> "This radio"
        "" -> "Unknown"
        else -> s.nodes.firstOrNull { it.id == id }?.name ?: id
    }
    private fun age(time: Long?): String {
        if (time == null || time <= 0) return "unknown age"
        val seconds = (System.currentTimeMillis() - time) / 1000
        return when {
            seconds < 0 -> "clock ahead"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
    }
    private fun distance(meters: Double?) = meters?.let { if (it < 1000) "≈${it.toInt()} m" else "≈%.1f km".format(Locale.US, it / 1000) } ?: "distance unknown"
    private fun clock(time: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(time))
    private fun recent(s: MonitorState) = s.events.filter { System.currentTimeMillis() - it.time in 0..600_000 && it.time >= s.started && it.context == s.context }

    private fun render(preserveScroll: Boolean = true) {
        if (preserveScroll && SystemClock.elapsedRealtime() < interactingUntil) return
        val s = state()
        val capturing = RadioMonitorService.instance != null
        heading.text = listOf("Radio", "Mesh", "Activity", "HARDLINE ATAK")[page]
        connection.text = if (!capturing) "Capture stopped · history available" else s.message
        navButtons.forEachIndexed { i, b ->
            b.setTextColor(if (i == page) bg else muted)
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(if (i == page) mint else Color.rgb(19, 30, 37))
        }
        if (page == 3 && body.getChildAt(0) === atak) return
        val scrollY = if (preserveScroll) (body.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        body.removeAllViews()
        if (page == 3) { body.addView(atak); return }
        val content = column().apply { setPadding(dp(20), dp(8), dp(20), dp(20)) }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true; addView(content)
            setOnTouchListener { _, _ -> interactingUntil = SystemClock.elapsedRealtime() + 1500; false }
        }
        body.addView(scroll)
        when (page) {
            0 -> radioPage(content, s, capturing)
            1 -> meshPage(content, s)
            2 -> activityPage(content, s)
        }
        scroll.post { scroll.scrollTo(0, scrollY) }
    }

    private fun radioPage(root: LinearLayout, s: MonitorState, capturing: Boolean) {
        val rx = recent(s).filter { it.direction == "RX" && it.transport == "LoRa" }
        val hero = card(root, "YOUR CONNECTION")
        val acknowledged = s.attempts.count { it.outcome == "Acknowledged" }
        val routing = s.attempts.count { it.outcome == "Relay acknowledgment" }
        val title = when {
            !capturing -> "Open a window into your radio."
            !s.connected -> "Waiting for your radio."
            s.surveying -> "Checking the mesh."
            routing > 0 && acknowledged == 0 -> "Routing activity confirmed."
            s.attempts.isNotEmpty() && acknowledged == 0 -> "No acknowledged checks yet."
            s.attempts.isNotEmpty() && acknowledged < s.attempts.size -> "Some checks went unanswered."
            acknowledged > 0 -> "Addressed checks were acknowledged."
            rx.isNotEmpty() -> "Your radio is hearing mesh traffic."
            else -> "Connected. Listening for traffic."
        }
        label(hero, title, 24f).typeface = Typeface.DEFAULT_BOLD
        label(hero, "${rx.map { it.source }.distinct().size} origins observed over LoRa in the last 10 minutes of this capture.", 14f, muted)
        if (!capturing) button(hero, "Start capture") { startCapture() }
        else if (s.surveying) button(hero, "Stop survey") { RadioMonitorService.instance?.stopSurvey() }
        else button(hero, "Check my connection") { RadioMonitorService.instance?.startSurvey() }.isEnabled = s.connected
        label(hero, s.surveyText, 14f, if (s.surveying) mint else muted)
        button(hero, "Why this assessment?") {
            explain("Evidence behind this assessment", "$acknowledged destination acknowledgments and $routing routing acknowledgments from ${s.attempts.size} checks.\n\n${rx.size} RF packet events from ${rx.map { it.source }.distinct().size} origins in this context.\n\nA routing acknowledgment may only mean a relay repeated the packet. The pinned API cannot reliably identify destination acknowledgments for non-chat diagnostics. Passive reception independently shows which nodes transmitted. Neither establishes future reachability. A timeout is unconfirmed, not proof of an offline node.")
        }
        val traffic = card(root, "OBSERVED ACTIVITY · 10 MIN")
        traffic.addView(TrafficChart(activity, recent(s)), LayoutParams(-1, dp(100)))
        label(traffic, "${rx.size} RF receptions  ·  ${recent(s).count { it.direction == "TX" }} diagnostic submissions", 16f)
        label(traffic, "Last RF event: ${age(rx.lastOrNull()?.time)}", 13f, muted)
        val health = card(root, "RADIO-REPORTED CONDITION")
        val battery = when (s.battery) { null, 0 -> "Unknown"; 101 -> "External power"; else -> "${s.battery}%" }
        label(health, "Power  $battery", 17f)
        label(health, "Channel busy  ${s.utilization?.let { "%.1f%%".format(Locale.US, it) } ?: "Unavailable"}", 17f)
        label(health, "TX airtime  ${s.airtime?.let { "%.1f%%".format(Locale.US, it) } ?: "Unavailable"}", 17f)
        label(health, "Uptime  ${s.uptime?.let { "${it / 3600}h ${(it % 3600) / 60}m" } ?: "Unavailable"}", 17f)
        label(health, "Metrics: ${age(s.metricTime)} · radio cache, not a fresh measurement on every refresh", 12f, muted)
        button(health, "What can Relay actually see?") { coverage() }
        val setup = card(root, "CURRENT RADIO")
        label(setup, s.nodes.firstOrNull { it.number == s.local }?.name ?: "No radio selected", 19f)
        label(setup, s.context, 14f, muted)
        label(setup, "Diagnostics preserve these settings. Nodes cached on another frequency may not answer here.", 13f, muted)
        if (capturing) {
            button(setup, "Refresh cached radio details") { RadioMonitorService.instance?.refreshRadio() }
            button(setup, "Stop capture") { activity.stopService(Intent(activity, RadioMonitorService::class.java)); render() }
        }
        button(setup, "Open Meshtastic") { activity.packageManager.getLaunchIntentForPackage("com.geeksville.mesh")?.let(activity::startActivity) }
    }

    private fun meshPage(root: LinearLayout, s: MonitorState) {
        val nodes = RadioEvidence.ranked(s.nodes.filter { it.number != s.local }, System.currentTimeMillis())
        val own = s.nodes.firstOrNull { it.number == s.local }
        val map = card(root, "OFFLINE NODE MAP")
        map.addView(NodeMap(activity, own, nodes, rangeKm) { nodeDetails(it, s) }, LayoutParams(-1, dp(280)))
        label(map, "Mint: acknowledged recently · Blue: heard over RF · Gray: cached\nNorth up · reported positions · no terrain or coverage prediction", 12f, muted)
        label(map, "Local position: ${age(own?.positionTime)}", 12f, muted)
        button(map, "Use phone GPS for map") { usePhoneGps() }
        button(map, "Map radius: ${rangeKm.toInt()} km") {
            val ranges = arrayOf("5 km", "10 km", "25 km", "50 km", "100 km", "250 km")
            AlertDialog.Builder(activity).setTitle("Map radius").setItems(ranges) { _, i ->
                rangeKm = listOf(5f, 10f, 25f, 50f, 100f, 250f)[i]; render()
            }.show()
        }
        if (nodes.isEmpty()) label(root, "No node metadata yet. Start capture and connect Meshtastic to your radio.", 16f, muted)
        label(root, "${nodes.size} KNOWN NODES", 12f, mint)
        nodes.take(nodeLimit).forEach { n ->
            val c = card(root)
            label(c, n.name, 20f).typeface = Typeface.DEFAULT_BOLD
            val evidence = nodeEvidence(n)
            label(c, evidence, 14f, if (n.acknowledged > 0) mint else if (n.observed > 0) blue else muted)
            val d = if (own?.latitude != null && own.longitude != null && n.latitude != null && n.longitude != null)
                RadioEvidence.distance(own.latitude!!, own.longitude!!, n.latitude!!, n.longitude!!) else null
            label(c, "${distance(d)} · position ${age(n.positionTime)}", 12f, muted)
            button(c, "Inspect node") { nodeDetails(n, s) }
        }
        if (nodes.size > nodeLimit) button(root, "Show 20 more nodes") { nodeLimit += 20; render() }
    }

    private fun nodeEvidence(n: RadioNode): String {
        val now = System.currentTimeMillis()
        return when {
            n.acknowledged > 0 && now - n.acknowledged in 0..600_000 -> "Acknowledged a check · ${age(n.acknowledged)}"
            n.observed > 0 && now - n.observed in 0..600_000 -> "Heard over RF · ${age(n.observed)}"
            n.observed > 0 -> "Earlier RF observation · ${age(n.observed)}"
            else -> "Cached only · last known ${age(n.lastKnown)}"
        }
    }

    private fun nodeDetails(n: RadioNode, s: MonitorState) {
        val layout = column().apply { setPadding(dp(20), dp(8), dp(20), dp(16)) }
        label(layout, n.id, 13f, muted)
        label(layout, nodeEvidence(n), 17f, mint)
        val samples = s.events.filter { it.source == n.id && it.direction == "RX" && it.context == s.context && it.time >= s.started }
        val attempts = s.attempts.filter { it.node == n.number }
        label(layout, "${attempts.count { it.outcome == "Acknowledged" }} of ${attempts.size} checks acknowledged in the latest survey", 16f)
        label(layout, "${samples.size} observed packet events in this capture/context. Final-hop signal samples:", 13f, muted)
        samples.takeLast(8).reversed().forEach {
            label(layout, "${clock(it.time)}  ${it.rssi?.let { v -> "$v dBm" } ?: "RSSI —"}  /  ${it.snr?.let { v -> "$v dB SNR" } ?: "SNR —"}  /  ${it.hops?.let { v -> "$v relays" } ?: "hops unknown"}", 12f)
        }
        attempts.forEach { label(layout, "Check ${attempts.indexOf(it) + 1}: ${it.outcome}${it.elapsed?.let { ms -> " · %.1fs".format(Locale.US, ms / 1000.0) } ?: ""}", 13f) }
        label(layout, "Signal belongs to the final reception. A strong relayed packet cannot identify the weakest hop. Cached positions and names are advertised metadata.", 13f, muted)
        button(layout, "Test this node · 4 checks") { RadioMonitorService.instance?.startSurvey(n.number) }.isEnabled = s.connected && !s.surveying
        button(layout, "Follow packet activity") { nodeFilter = n.id; page = 2; render(false) }
        AlertDialog.Builder(activity).setTitle(n.name).setView(ScrollView(activity).apply { addView(layout) }).setPositiveButton("Done", null).show()
    }

    private fun activityPage(root: LinearLayout, s: MonitorState) {
        val all = paused ?: s.events
        val summary = card(root, if (paused != null) "VIEW PAUSED · CAPTURE CONTINUES" else "LIVE METADATA")
        summary.addView(TrafficChart(activity, all), LayoutParams(-1, dp(90)))
        val window = all.filter { System.currentTimeMillis() - it.time in 0..600_000 }
        label(summary, "${window.count { it.direction == "RX" }} incoming · ${window.count { it.direction == "TX" }} submissions · last 10 min", 16f)
        label(summary, "${all.size} stored events · up to 7 days / 5,000 events · bodies never stored", 12f, muted)
        button(summary, if (paused == null) "Pause scrolling" else "Resume live view") { paused = if (paused == null) s.events else null; render() }
        button(summary, "Filter: ${nodeFilter?.let { name(it, s) } ?: filter}") {
            val options = arrayOf("All activity", "Incoming", "Outgoing", "Problems", "Latest survey", "LoRa only", "MQTT", "Events")
            AlertDialog.Builder(activity).setTitle("Show activity").setItems(options) { _, i -> filter = options[i]; nodeFilter = null; displayLimit = 60; render(false) }.show()
        }
        button(summary, "Insights & tools") {
            AlertDialog.Builder(activity).setTitle("Activity tools").setItems(arrayOf("Traffic insights", "Mark antenna / location change", "Export metadata", "Capture coverage")) { _, i ->
                when (i) {
                    0 -> insights(s, all)
                    1 -> { RadioMonitorService.instance?.markComparison(); baseline = System.currentTimeMillis() }
                    2 -> export(all)
                    3 -> coverage()
                }
            }.show()
        }
        val filtered = all.filter { e ->
            if (nodeFilter != null) e.source == nodeFilter || e.destination == nodeFilter
            else when (filter) {
                "Incoming" -> e.direction == "RX"
                "Outgoing" -> e.direction == "TX" || e.direction == "STATUS"
                "Problems" -> e.outcome in listOf("Failed", "Unconfirmed") || e.kind.contains("gap", true)
                "Latest survey" -> s.surveyId.isNotEmpty() && e.survey == s.surveyId
                "LoRa only" -> e.transport == "LoRa"
                "MQTT" -> e.mqtt
                "Events" -> e.direction == "EVENT"
                else -> true
            }
        }
        if (filtered.isEmpty()) label(root, "No matching events yet. Incoming traffic appears as Meshtastic exposes it. Cached node entries do not create packet events.", 15f, muted)
        filtered.takeLast(displayLimit).reversed().forEach { e ->
            val c = card(root)
            val tint = when (e.direction) { "RX" -> mint; "TX" -> blue; "STATUS" -> amber; else -> muted }
            label(c, "${e.direction}  ·  ${clock(e.time)}  ·  ${e.kind}", 12f, tint)
            label(c, when (e.direction) {
                "RX" -> "${name(e.source, s)} → ${name(e.destination, s)}"
                "TX" -> "Checking ${name(e.destination, s)}"
                "STATUS" -> e.outcome
                else -> e.kind
            }, 19f).typeface = Typeface.DEFAULT_BOLD
            if (e.direction == "RX") label(c, "${e.rssi?.let { "$it dBm" } ?: "RSSI —"} · ${e.snr?.let { "$it dB SNR" } ?: "SNR —"} · ${e.hops?.let { "$it relays observed" } ?: "hops unknown"}", 13f, muted)
            else label(c, e.note, 13f, muted)
            button(c, "Details") { eventDetails(e, s) }
        }
        if (filtered.size > displayLimit) button(root, "Show 60 more events") { displayLimit += 60; render() }
        button(root, "Clear local history") {
            AlertDialog.Builder(activity).setTitle("Clear packet metadata?").setMessage("Deletes this device's stored activity. Saved channel profiles are separate.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    val monitor = RadioMonitorService.instance
                    if (monitor != null) monitor.clearHistory() else ObservationStore(activity).use { it.clear() }
                    paused = null; lastState = lastState.copy(events = emptyList()); render()
                }.show()
        }
    }

    private fun eventDetails(e: RadioEvent, s: MonitorState) {
        explain(e.kind, "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(e.time))}\n" +
            "${e.direction} · ${e.outcome}\n${name(e.source, s)} ${e.source}\nTo: ${name(e.destination, s)} ${e.destination}\n\n" +
            "Packet ID: ${e.packetId.toUInt()}\nChannel index: ${e.channel.takeIf { it >= 0 } ?: "unknown"}\nExposed payload: ${if (e.size < 0) "unknown" else "${e.size} bytes"} (not total RF size)\n" +
            "RSSI: ${e.rssi ?: "unavailable"} dBm\nSNR: ${e.snr ?: "unavailable"} dB\n" +
            "Observed relays: ${e.hops ?: "unknown"}\nRemaining hop allowance: ${e.hopLimit ?: "unknown"}\n" +
            "Last relay byte: ${e.relay?.toString(16) ?: "unknown"} (partial ID; may be ambiguous)\n" +
            "Origin distance at observation: ${distance(e.distance)}\nPosition used: ${age(e.positionTime)}\n" +
            "Transport: ${e.transport}\n${e.context}\nSurvey: ${e.survey.ifEmpty { "none" }}\n" +
            (e.elapsed?.let { "Status elapsed: %.1f seconds\n".format(Locale.US, it / 1000.0) } ?: "") + "\n${e.note}")
    }

    private fun insights(s: MonitorState, all: List<RadioEvent>) {
        val rx = all.filter { it.direction == "RX" && it.time >= s.started && it.context == s.context }
        val talkers = rx.groupBy { it.source }.entries.sortedByDescending { it.value.size }.take(5)
            .joinToString("\n") { "${name(it.key, s)}: ${it.value.size} events / ${it.value.sumOf { e -> e.size.coerceAtLeast(0) }} payload bytes" }
        val types = rx.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }.take(6)
            .joinToString("\n") { "${it.key}: ${it.value}" }
        val farthest = rx.filter { it.hops == 0 && it.transport == "LoRa" && it.distance != null && it.positionTime != null &&
            it.time - it.positionTime!! in 0..600_000 }.maxByOrNull { it.distance ?: 0.0 }
        val marker = baseline ?: all.lastOrNull { it.kind == "Comparison marker" }?.time
        val comparison = marker?.let { time ->
            val period = (System.currentTimeMillis() - time).coerceAtMost(600_000).coerceAtLeast(1)
            val before = rx.filter { it.time in (time - period) until time }
            val after = rx.filter { it.time in time..(time + period) }
            "\n\nAROUND YOUR MARKER · equal ${period / 1000}s windows\n${before.size} receptions before / ${after.size} after. Traffic load and sender mix can differ; this alone is not a link-quality test."
        } ?: ""
        explain("This capture · current radio context", "MOST OBSERVED\n${talkers.ifEmpty { "No incoming observations yet." }}\n\nTRAFFIC MIX\n${types.ifEmpty { "Waiting for traffic." }}\n\nFARTHEST DIRECT ORIGIN\n" +
            (farthest?.let { "${name(it.source, s)} · ${distance(it.distance)} · position under 10 minutes old at reception" } ?: "No qualifying direct reception with a recent position.") + comparison)
    }

    private fun coverage() = explain("Capture coverage", "Relay records packet events exposed by Meshtastic and its own diagnostic submissions. It is not a raw RF capture.\n\nCorrupt receptions, firmware retries/rebroadcasts, undecodable traffic and other apps' outgoing bodies are not fully exposed. Native traceroutes are consumed inside Meshtastic. Local malformed/duplicate counters are not available through this pinned API.\n\nRadio battery and airtime readings come from cached telemetry and show their age. Zero SNR can be a real reading; missing signal fields remain unknown.\n\nThe exported Meshtastic broadcast API is not sender-authenticated. Use a trusted phone. Collection continues with the visible notification until Stop capture. No survey starts automatically; stopping a survey cannot retract firmware-queued packets.")
}
