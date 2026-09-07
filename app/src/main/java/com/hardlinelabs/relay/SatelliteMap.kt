package com.hardlinelabs.relay

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import com.hardlinelabs.relay.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Packaged map code only. Online requests contain tile coordinates, never node metadata. */
@SuppressLint("SetJavaScriptEnabled")
internal class SatelliteMap(context: Context, private val inspect: (RadioNode) -> Unit,
                            gps: () -> Unit) : LinearLayout(context) {
    private val ui = RadioUi(context)
    private val web = WebView(context)
    private var ready = false
    private var pendingFocus: String? = null
    private var state = MonitorState()
    private var significant = emptyList<RadioNode>()
    private val candidates = ui.list()
    private val status: TextView
    private val panelTitle: Button
    private var panelOpen = true
    private val adapter = object : BaseAdapter() {
        override fun getCount() = significant.size
        override fun getItem(position: Int) = significant[position]
        override fun getItemId(position: Int) = significant[position].number.toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? TextView ?: TextView(context).apply { setTextColor(ui.ink); textSize = 13f; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); minHeight = ui.dp(50) }).apply {
                val n = significant[position]
                val ack = n.acknowledged > 0 && System.currentTimeMillis() - n.acknowledged in 0..600_000
                text = "${n.name}\n${if (ack) "Destination acknowledged" else "Repeated direct receptions"} · ${age(if (ack) n.acknowledged else n.observed)}"
            }
    }
    init {
        orientation = VERTICAL
        val controls = ui.row(); addView(controls)
        ui.button(controls, "Fit nodes") { web.evaluateJavascript("relayFit()", null) }
        ui.button(controls, "My GPS") { gps(); web.evaluateJavascript("relayLocal()", null) }
        ui.button(controls, "Labels") { web.evaluateJavascript("relayLabels()", null) }
        ui.button(controls, "Reload") { web.evaluateJavascript("relayReload()", null) }
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            domStorageEnabled = false; javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false); cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net") {
                    val asset = uri.path?.removePrefix("/map/")
                    val type = when (asset) { "map.html" -> "text/html"; "map.js", "leaflet.js" -> "application/javascript"; "map.css", "leaflet.css" -> "text/css"; else -> null }
                    if (type != null && uri.path == "/map/$asset") return WebResourceResponse(type, "UTF-8", context.assets.open("map/$asset"))
                }
                if (uri.scheme == "https" && uri.host == "basemap.nationalmap.gov" && request.method == "GET" &&
                    Regex("/arcgis/rest/services/USGSImagery(Only|Topo)/MapServer/tile/[0-9]+/[0-9]+/[0-9]+").matches(uri.path.orEmpty())) return null
                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (request.isForMainFrame && uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.pathSegments.firstOrNull() == "inspect") {
                    val id = uri.pathSegments.getOrNull(1)
                    state.nodes.firstOrNull { it.id == id }?.let(inspect)
                }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) { ready = true; update(state) }
        }
        web.setBackgroundColor(ui.bg); web.tag = "satellite-map"
        addView(web, LayoutParams(-1, 0, 1f))
        panelTitle = ui.button(this, "Significant relays · no location ▾") { panelOpen = !panelOpen; updatePanel() }
        status = ui.label(this, size = 11f, color = ui.muted).apply { setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        addView(candidates, LayoutParams(-1, ui.dp(116))); candidates.adapter = adapter
        candidates.setOnItemClickListener { _, _, position, _ -> inspect(significant[position]) }
        web.loadUrl("https://appassets.androidplatform.net/map/map.html")
    }
    private fun updatePanel() {
        candidates.visibility = if (panelOpen && significant.isNotEmpty()) VISIBLE else GONE
        status.visibility = if (panelOpen) VISIBLE else GONE
        panelTitle.update("Significant relays · ${significant.size} unlocated ${if (panelOpen) "▾" else "▴"}")
    }
    fun update(s: MonitorState) {
        state = s
        val now = System.currentTimeMillis()
        val next = RadioEvidence.significantUnlocated(s.nodes, s.events, s.local, s.context, s.started, now)
        if (next != significant) { significant = next; adapter.notifyDataSetChanged() }
        status.update(if (significant.isEmpty()) "No qualifying unlocated nodes yet. All known nodes remain in Mesh." else "Candidates from recent direct RF / test evidence; forwarding ability is unverified.")
        updatePanel()
        if (!ready) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val data = JSONObject().put("local", s.local).put("online", online).put("nodes", JSONArray().apply {
            s.nodes.filter { it.latitude != null && it.longitude != null && RadioEvidence.validPosition(it.latitude!!, it.longitude!!) && it.latitude!! in -85.0..85.0 }.forEach { n ->
                put(JSONObject().put("number", n.number).put("id", n.id).put("name", n.name).put("lat", n.latitude).put("lon", n.longitude)
                    .put("precision", n.positionPrecision).put("position", positionEvidence(n)).put("evidence", nodeEvidence(n))
                    .put("freshness", when {
                        n.acknowledged > 0 && now - n.acknowledged in 0..600_000 -> "Ack · ${age(n.acknowledged)}"
                        n.observed > 0 -> "RF · ${age(n.observed)}"
                        else -> "Cached · ${age(n.lastKnown)}"
                    })
                    .put("color", when { n.acknowledged > 0 && now - n.acknowledged in 0..600_000 -> "#5ddac4"; n.observed > 0 && now - n.observed in 0..600_000 -> "#74aaff"; else -> "#81919b" }))
            }
        })
        web.evaluateJavascript("relayUpdate($data)", null)
        pendingFocus?.let { id -> web.evaluateJavascript("relayFocus(${JSONObject.quote(id)})", null); pendingFocus = null }
    }
    fun focus(id: String) { pendingFocus = id; update(state) }
    fun resumeMap() { web.onResume() }
    fun pauseMap() { web.onPause() }
    fun destroy() { web.stopLoading(); web.destroy() }
}
