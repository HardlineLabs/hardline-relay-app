package com.hardlinelabs.relay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.*
import com.hardlinelabs.relay.core.*
import java.text.SimpleDateFormat
import java.util.*

/** Shared visual vocabulary; all live pages keep their view instances. */
internal class RadioUi(val context: Context) {
    val ink = Color.rgb(237, 242, 243)
    val muted = Color.rgb(153, 174, 185)
    val mint = Color.rgb(93, 218, 196)
    val blue = Color.rgb(116, 170, 255)
    val bg = Color.rgb(13, 21, 27)
    fun dp(n: Int) = (context.resources.displayMetrics.density * n).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
    fun label(parent: LinearLayout, value: String = "", size: Float = 14f, color: Int = ink) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(4), 0, dp(4)); parent.addView(this)
    }
    fun card(parent: LinearLayout, title: String? = null) = column().apply {
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply { setColor(Color.rgb(24, 36, 44)); cornerRadius = dp(14).toFloat() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        if (title != null) label(this, title, 12f, mint).typeface = Typeface.DEFAULT_BOLD
    }
    fun button(parent: LinearLayout, title: String, action: () -> Unit) = Button(context).apply {
        text = title; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
        setPadding(dp(6), 0, dp(6), 0); minHeight = dp(48)
        setTextColor(ink); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(43, 64, 74))
        setOnClickListener { action() }
        parent.addView(this, if (parent.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, dp(48), 1f) else LinearLayout.LayoutParams(-1, dp(48)))
    }
    fun list() = ListView(context).apply {
        divider = null; dividerHeight = 0; isVerticalScrollBarEnabled = true
        setPadding(dp(12), dp(6), dp(12), dp(6)); clipToPadding = false
        setBackgroundColor(bg); transcriptMode = AbsListView.TRANSCRIPT_MODE_DISABLED
    }
    fun single(text: TextView) { text.maxLines = 1; text.ellipsize = TextUtils.TruncateAt.END }
}

internal fun TextView.update(value: String) { if (text.toString() != value) text = value }
internal fun Button.available(value: Boolean) { isEnabled = value; alpha = if (value) 1f else .45f }
internal fun nodeName(id: String, s: MonitorState): String = when (id) {
    "^all", "!ffffffff" -> "Broadcast"
    "^local" -> "This radio"
    "" -> "Unknown"
    else -> s.nodes.firstOrNull { it.id == id }?.name ?: id
}
internal fun age(time: Long?): String {
    if (time == null || time <= 0) return "unknown age"
    val seconds = (System.currentTimeMillis() - time) / 1000
    return when { seconds < 0 -> "clock ahead"; seconds < 60 -> "${seconds}s ago"; seconds < 3600 -> "${seconds / 60}m ago"; seconds < 86400 -> "${seconds / 3600}h ago"; else -> "${seconds / 86400}d ago" }
}
internal fun clock(time: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(time))
internal fun distance(meters: Double?) = meters?.let { if (it < 1000) "≈${it.toInt()} m" else "≈%.1f km".format(Locale.US, it / 1000) } ?: "distance unknown"
internal fun nodeEvidence(n: RadioNode): String = when {
    n.acknowledged > 0 && System.currentTimeMillis() - n.acknowledged in 0..600_000 -> "Destination acknowledgment · ${age(n.acknowledged)}"
    n.observed > 0 -> "Heard over RF · ${age(n.observed)}"
    else -> "Cached only · ${age(n.lastKnown)}"
}
internal fun positionEvidence(n: RadioNode): String {
    if (n.latitude == null || n.longitude == null) return "No reported location"
    val precision = n.positionPrecision?.let { if (it < 32) " · coarse ($it bits)" else " · full coordinate precision" } ?: ""
    return "${n.positionSource}$precision · ${age(n.positionTime)}"
}
internal fun hopsLabel(e: RadioEvent) = e.hops?.let { "Hops $it" } ?: "Hops unknown"
