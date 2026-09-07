package com.hardlinelabs.relay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.hardlinelabs.relay.core.RadioEvent

/** Fixed time buckets and scale: scrolling and quiet periods never resize existing bars. */
class TrafficChart(context: Context, events: List<RadioEvent> = emptyList()) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var events = events
    private var endBucket = System.currentTimeMillis() / 20_000
    fun update(events: List<RadioEvent>, now: Long = System.currentTimeMillis()) {
        val bucket = now / 20_000
        if (this.events == events && endBucket == bucket) return
        this.events = events; endBucket = bucket; invalidate()
    }
    init { contentDescription = "Ten-minute packet chart. Fixed scale: 10 packets per 20-second bucket; taller counts capped. Mint RX, blue TX." }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val rx = IntArray(30); val tx = IntArray(30)
        events.forEach { e ->
            val index = (29 - (endBucket - e.time / 20_000)).toInt()
            if (index in 0..29) {
                if (e.direction == "RX") rx[index]++
                if (e.direction == "TX") tx[index]++
            }
        }
        val unit = width / 30f
        for (i in 0..29) {
            paint.color = Color.rgb(38, 57, 67)
            c.drawRect(i * unit + 2, 8f, (i + 1) * unit - 2, height - 24f, paint)
            paint.color = Color.rgb(93, 218, 196)
            c.drawRect(i * unit + 2, height - 24 - rx[i].coerceAtMost(10) / 10f * (height - 36), i * unit + unit / 2, height - 24f, paint)
            paint.color = Color.rgb(116, 170, 255)
            c.drawRect(i * unit + unit / 2, height - 24 - tx[i].coerceAtMost(10) / 10f * (height - 36), (i + 1) * unit - 2, height - 24f, paint)
        }
        paint.color = Color.rgb(159, 180, 190); paint.textSize = 10 * resources.displayMetrics.scaledDensity
        c.drawText("10 min · 10 packets / bar max", 0f, height - 3f, paint)
        c.drawText("now", width - paint.measureText("now"), height - 3f, paint)
    }
}
