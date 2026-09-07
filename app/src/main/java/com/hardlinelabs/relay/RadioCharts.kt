package com.hardlinelabs.relay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.hardlinelabs.relay.core.RadioEvent
import com.hardlinelabs.relay.core.RadioNode
import kotlin.math.*

/** Small native charts need no network, map account, or new runtime dependency. */
class TrafficChart(context: Context, private val events: List<RadioEvent>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = "Ten-minute activity chart. Mint bars are received events; blue bars are outgoing submissions." }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = System.currentTimeMillis()
        val rx = IntArray(30); val tx = IntArray(30)
        events.forEach { e ->
            val age = now - e.time
            if (age in 0 until 600_000) {
                val i = 29 - (age / 20_000).toInt()
                if (e.direction == "RX") rx[i]++
                if (e.direction == "TX") tx[i]++
            }
        }
        val maximum = (rx.maxOrNull() ?: 1).coerceAtLeast(tx.maxOrNull() ?: 1).coerceAtLeast(1)
        val unit = width / 30f
        for (i in 0..29) {
            paint.color = Color.rgb(38, 57, 67)
            c.drawRect(i * unit + 2, 8f, (i + 1) * unit - 2, height - 24f, paint)
            paint.color = Color.rgb(93, 218, 196)
            c.drawRect(i * unit + 2, height - 24 - rx[i].toFloat() / maximum * (height - 36), i * unit + unit / 2, height - 24f, paint)
            paint.color = Color.rgb(116, 170, 255)
            c.drawRect(i * unit + unit / 2, height - 24 - tx[i].toFloat() / maximum * (height - 36), (i + 1) * unit - 2, height - 24f, paint)
        }
        paint.color = Color.rgb(159, 180, 190); paint.textSize = 11 * resources.displayMetrics.scaledDensity
        c.drawText("10 min ago", 0f, height - 3f, paint)
        c.drawText("now", width - paint.measureText("now"), height - 3f, paint)
    }
}

class NodeMap(context: Context, private val own: RadioNode?, private val nodes: List<RadioNode>,
              private val rangeKm: Float, private val select: (RadioNode) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val points = mutableListOf<Triple<Float, Float, RadioNode>>()
    init { contentDescription = "Offline geographic node map, north up. Radius $rangeKm kilometers. Nodes are also available in the list below." }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        points.clear()
        val cx = width / 2f; val cy = height / 2f
        val radius = min(width, height) * .42f
        paint.color = Color.rgb(18, 31, 38); c.drawColor(paint.color)
        paint.color = Color.rgb(42, 66, 74); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        for (i in 1..3) c.drawCircle(cx, cy, radius * i / 3, paint)
        c.drawLine(cx - radius, cy, cx + radius, cy, paint); c.drawLine(cx, cy - radius, cx, cy + radius, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(159, 180, 190); paint.textSize = 12 * resources.displayMetrics.scaledDensity
        c.drawText("N", cx - 5, cy - radius - 8, paint)
        c.drawText("${rangeKm.toInt()} km radius", 12f, height - 12f, paint)
        val fallback = nodes.firstOrNull { it.latitude != null && it.longitude != null }
        val lat = own?.latitude ?: fallback?.latitude
        val lon = own?.longitude ?: fallback?.longitude
        if (lat == null || lon == null) {
            c.drawText("No reported positions yet", 16f, cy, paint)
            return
        }
        if (own?.latitude == null) {
            paint.textSize = 10 * resources.displayMetrics.scaledDensity
            c.drawText("Centered on ${fallback?.name?.take(20)} · your position unknown", 12f, height - 40f, paint)
        }
        val now = System.currentTimeMillis()
        // Local equirectangular projection. This map is geographic context, never a coverage prediction.
        nodes.filter { it.number != own?.number && it.latitude != null && it.longitude != null }.forEach { n ->
            val dy = (n.latitude!! - lat) * 111.195
            val dlon = ((n.longitude!! - lon + 540) % 360) - 180
            val dx = dlon * 111.195 * cos(Math.toRadians(lat))
            val x = cx + (dx / rangeKm * radius).toFloat()
            val y = cy - (dy / rangeKm * radius).toFloat()
            if (hypot(x - cx, y - cy) <= radius) {
                paint.color = when {
                    n.acknowledged > 0 && now - n.acknowledged < 600_000 -> Color.rgb(93, 218, 196)
                    n.observed > 0 && now - n.observed < 600_000 -> Color.rgb(116, 170, 255)
                    else -> Color.rgb(99, 113, 124)
                }
                c.drawCircle(x, y, 5 * resources.displayMetrics.density, paint)
                points.add(Triple(x, y, n))
                if (nodes.size < 30 || n.observed > 0) {
                    paint.textSize = 10 * resources.displayMetrics.scaledDensity
                    c.drawText(n.name.take(12), x + 9, y - 9, paint)
                }
            }
        }
        if (own?.latitude != null) {
            paint.color = Color.WHITE
            c.drawCircle(cx, cy, 5 * resources.displayMetrics.density, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            points.minByOrNull { hypot(it.first - event.x, it.second - event.y) }
                ?.takeIf { hypot(it.first - event.x, it.second - event.y) < 28 * resources.displayMetrics.density }
                ?.let { select(it.third) }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
