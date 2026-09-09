package com.hardlinelabs.relay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

data class CongestionSample(val time: Long, val busy: Float, val context: String)

/** Actual telemetry samples only. Missing time is empty space, never an invented zero. */
class CongestionChart(context: Context) : LinearLayout(context) {
    private var samples = emptyList<CongestionSample>()
    private var visible = emptyList<CongestionSample>()
    private var window = 60 * 60_000L
    private var end = System.currentTimeMillis()
    private var selectedTime: Long? = null
    private var radio = 0
    private var following = true
    private val ink = Color.rgb(237, 242, 243)
    private val detail = TextView(context).apply { setTextColor(ink); textSize = 13f }
    private val slider = SeekBar(context).apply { contentDescription = "Inspect channel congestion samples"; tag = "congestion-scrubber" }
    private val graph = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val bottom = height - 22f
            paint.strokeWidth = 1f; paint.textSize = 11 * resources.displayMetrics.scaledDensity
            for (level in listOf(0, 50, 100)) {
                val y = 10 + (100 - level) / 100f * (bottom - 10)
                paint.color = Color.rgb(59, 76, 84); c.drawLine(40f, y, width.toFloat(), y, paint)
                paint.color = Color.rgb(159, 180, 190); c.drawText("$level", 0f, y.coerceAtLeast(15f), paint)
            }
            visible.forEach {
                val x = 40 + ((it.time - (end - window)).toDouble() / window * (width - 44)).toFloat()
                val y = 10 + (100 - it.busy) / 100f * (bottom - 10)
                paint.color = if (it.busy >= 50) Color.rgb(255, 195, 100) else Color.rgb(93, 218, 196)
                c.drawCircle(x, y, if (it.time == selectedTime) 7f else 4f, paint)
            }
        }
    }
    init {
        orientation = VERTICAL
        val windows = listOf("10 minutes", "1 hour", "6 hours", "24 hours", "7 days")
        val lengths = listOf(600_000L, 3_600_000L, 21_600_000L, 86_400_000L, 604_800_000L)
        val choose = Button(context).apply { text = "History · 1 hour"; isAllCaps = false; setTextColor(ink) }
        choose.setOnClickListener {
            android.app.AlertDialog.Builder(context).setTitle("Congestion history").setItems(windows.toTypedArray()) { _, i ->
                window = lengths[i]; choose.text = "History · ${windows[i]}"; selectedTime = null; following = true; refresh()
            }.show()
        }
        addView(choose); addView(graph, LayoutParams(-1, (130 * resources.displayMetrics.density).toInt()))
        addView(slider)
        addView(Button(context).apply { text = "Latest reading"; isAllCaps = false; setTextColor(ink); setOnClickListener { following = true; refresh() } })
        addView(detail)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
            override fun onProgressChanged(s: SeekBar, progress: Int, user: Boolean) {
                if (user) { following = false; selectedTime = visible.getOrNull(progress)?.time; describe(); graph.invalidate() }
            }
        })
    }
    fun update(node: Int, samples: List<CongestionSample>, now: Long = System.currentTimeMillis()) {
        if (node != radio) { radio = node; selectedTime = null; following = true }
        if (this.samples == samples && now / 10_000 == end / 10_000) return
        this.samples = samples; end = now; refresh()
    }
    private fun refresh() {
        visible = samples.filter { it.time in (end - window)..end }
        slider.max = (visible.size - 1).coerceAtLeast(0); slider.isEnabled = visible.size > 1
        val index = if (following) visible.lastIndex else visible.indexOfFirst { it.time == selectedTime }.takeIf { it >= 0 } ?: visible.lastIndex
        selectedTime = visible.getOrNull(index)?.time; slider.progress = index.coerceAtLeast(0)
        describe(); graph.invalidate()
    }
    private fun describe() {
        val sample = visible.firstOrNull { it.time == selectedTime }
        detail.text = if (sample == null) "No readings in this window. Start capture; new local radio telemetry appears here. Gaps are unknown." else {
            val date = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date(sample.time))
            "${"%.1f".format(Locale.US, sample.busy)}% channel busy · $date\n${sample.context}\n${visible.size} readings · peak ${"%.1f".format(Locale.US, visible.maxOf { it.busy })}% · gaps are unknown"
        }
        graph.contentDescription = detail.text
    }
}
