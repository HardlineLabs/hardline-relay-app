package com.hardlinelabs.relay

import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.widget.*
import com.hardlinelabs.relay.core.RadioNode
import java.util.Locale

/** The test controls and four attempts stay in view; only supplementary evidence scrolls. */
internal class NodeInspector(context: Context, val number: Int, close: () -> Unit,
                             private val start: (Int) -> Unit, private val stop: () -> Unit,
                             private val showMap: (RadioNode) -> Unit = {}) : LinearLayout(context) {
    private val ui = RadioUi(context)
    private val title: TextView
    private val mapButton: Button
    private var node: RadioNode? = null
    private val evidence: TextView
    private val test: Button
    private val stopButton: Button
    private val progress: ProgressBar
    private val summary: TextView
    private val checks = mutableListOf<TextView>()
    private val metadata: TextView
    private val history: TextView
    private var requested = false
    private var requestAt = 0L
    init {
        orientation = VERTICAL; setBackgroundColor(ui.bg); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
        tag = "node-inspector"
        val header = ui.row(); addView(header)
        ui.button(header, "‹ Back", close)
        mapButton = ui.button(header, "Show on map") { node?.let(showMap) }.apply { tag = "node-map" }
        title = ui.label(this, size = 23f).apply { typeface = Typeface.DEFAULT_BOLD; ui.single(this) }
        evidence = ui.label(this, size = 13f, color = ui.mint).apply { setLines(2) }
        val suite = ui.card(this, "NODE TEST")
        val controls = ui.row(); suite.addView(controls)
        test = ui.button(controls, "Test · 4 checks") {
            requested = true; requestAt = SystemClock.elapsedRealtime(); test.isEnabled = false
            summary.update("Starting addressed checks…"); start(number)
        }.apply { tag = "node-test" }
        stopButton = ui.button(controls, "Stop", stop).apply { tag = "node-stop" }
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 4 }
        suite.addView(progress, LayoutParams(-1, ui.dp(12)))
        repeat(4) { checks.add(ui.label(suite, "${it + 1} · Not started", 13f).apply { ui.single(this) }) }
        summary = ui.label(suite, size = 12f, color = ui.muted).apply { minLines = 3; maxLines = 4 }
        val scroll = ScrollView(context).apply { isFillViewport = true; tag = "node-evidence-scroll" }
        addView(scroll, LayoutParams(-1, 0, 1f))
        val content = ui.column(); scroll.addView(content)
        metadata = ui.label(content, size = 13f, color = ui.muted)
        ui.label(content, "RECENT RECEPTIONS", 12f, ui.mint)
        history = ui.label(content, size = 13f)
        ui.label(content, "Signal describes the final reception. Routing acknowledgments do not identify the destination; observed traffic is separate evidence. Tests never send public chat.", 12f, ui.muted)
    }
    fun update(s: MonitorState) {
        val n = s.nodes.firstOrNull { it.number == number }
        node = n
        val positioned = n?.latitude != null && n.longitude != null
        mapButton.update(if (positioned) "Show on map" else "No reported location")
        mapButton.available(positioned)
        title.update(n?.name ?: "Node unavailable")
        evidence.update(n?.let { "${it.id}\n${nodeEvidence(it)}" } ?: "Reconnect to refresh this node.")
        val here = number in s.surveyTargets
        val attempts = if (here) s.attempts.filter { it.node == number } else emptyList()
        val running = here && s.surveying
        if (requested && (here && s.surveying || SystemClock.elapsedRealtime() - requestAt > 1500)) requested = false
        test.available(n != null && s.connected && !s.surveying && !requested && n.number != s.local)
        stopButton.available(running)
        progress.progress = attempts.count { it.outcome != "Awaiting acknowledgment" }
        checks.forEachIndexed { i, label ->
            val a = attempts.getOrNull(i)
            val elapsed = a?.elapsed ?: a?.takeIf { it.outcome == "Awaiting acknowledgment" }?.let { SystemClock.elapsedRealtime() - it.sent }
            label.update("${i + 1} · ${a?.outcome ?: "Not started"}${elapsed?.let { " · %.0fs".format(Locale.US, it / 1000.0) } ?: ""}")
        }
        val rx = s.events.filter { it.direction == "RX" && it.transport == "LoRa" && it.source == n?.id && it.context == s.context && it.time >= s.started }
        val heard = if (here) rx.count { it.survey == s.surveyId } else 0
        if (!requested) summary.update(when {
            running -> "${s.surveyText}\n$heard receptions from this origin during the survey."
            s.surveying -> "A survey is running for other nodes. This inspector stays live."
            here -> "${attempts.count { it.outcome == "Acknowledged" }} destination · ${attempts.count { it.outcome == "Relay acknowledgment" }} routing acknowledgments · $heard RF receptions.\n" +
                when {
                    s.surveyText.startsWith("Wait") -> s.surveyText
                    s.surveyStopReason != null -> "${s.surveyStopReason}. ${attempts.size} checks submitted; no more will start."
                    else -> "${attempts.size} checks finished. ${if (attempts.none { it.outcome == "Acknowledged" }) "Destination confirmation was not exposed." else "Future delivery is not guaranteed."}"
                }
            else -> s.surveyText
        })
        metadata.update(n?.let { "${positionEvidence(it)}\nLast RF reception: ${age(it.observed)}\nLast destination acknowledgment: ${age(it.acknowledged)}\nCached last heard: ${age(it.lastKnown)}\n${s.context}" } ?: "Node metadata unavailable")
        history.update(rx.takeLast(8).reversed().joinToString("\n\n") {
            "${clock(it.time)} · ${it.kind}\n${it.rssi?.let { v -> "$v dBm" } ?: "RSSI unknown"} · ${it.snr?.let { v -> "$v dB SNR" } ?: "SNR unknown"} · ${hopsLabel(it)}"
        }.ifEmpty { "No RF receptions observed from this origin during this capture." })
    }
}
