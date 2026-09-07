package com.hardlinelabs.relay

import android.app.*
import android.content.*
import android.os.*
import com.hardlinelabs.relay.core.*
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class MonitorState(
    val connected: Boolean = false,
    val message: String = "Opening Meshtastic connection…",
    val context: String = "Radio settings unavailable",
    val local: Int = 0,
    val nodes: List<RadioNode> = emptyList(),
    val events: List<RadioEvent> = emptyList(),
    val battery: Int? = null,
    val utilization: Float? = null,
    val airtime: Float? = null,
    val uptime: Int? = null,
    val metricTime: Long = 0,
    val surveyId: String = "",
    val surveyText: String = "Run a check to measure addressed communication.",
    val surveying: Boolean = false,
    val attempts: List<RadioSurvey.Attempt> = emptyList(),
    val surveyTargets: List<Int> = emptyList(),
    val started: Long = System.currentTimeMillis(),
)

/** One serial worker owns observations and probes. The activity only reads immutable snapshots. */
class RadioMonitorService : Service() {
    companion object {
        @Volatile var instance: RadioMonitorService? = null
            private set
        private const val PREFIX = "com.geeksville.mesh"
        const val STOP = "com.hardlinelabs.relay.STOP_CAPTURE"
    }
    @Volatile var state = MonitorState()
        private set
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private lateinit var history: ObservationStore
    private val events = mutableListOf<RadioEvent>()
    private val nodes = linkedMapOf<Int, RadioNode>()
    private var mesh: IMeshService? = null
    private var bound = false
    private var receiverRegistered = false
    private var radio: MeshChannelClient.Snapshot? = null
    private var fingerprint = ""
    private var survey: RadioSurvey? = null
    private var announcedResult = ""
    private var lastPoll = 0L
    private var lastSurveyEnd = -30_000L
    private val seen = LinkedHashMap<String, Long>()
    private val statusSeen = LinkedHashMap<String, Long>()
    private var lastDecodeWarning = -60_000L
    private var phoneFix: Triple<Double, Double, Long>? = null
    @Volatile private var stopping = false
    @Volatile private var probesPaused = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        history = ObservationStore(this)
        instance = this
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("radio-capture", "Radio activity", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RadioMonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(51, Notification.Builder(this, "radio-capture").setSmallIcon(R.drawable.ic_hardline)
            .setContentTitle("Relay · packet activity")
            .setContentText("Recording metadata locally. Surveys transmit only when started.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop capture", stop).build()).build())
        registerPackets()
        worker.execute {
            events.addAll(history.read())
            record("Capture started", "Collection begins here. Earlier gaps contain no inferred traffic.")
            publish()
        }
        bindMesh()
        worker.scheduleWithFixedDelay({
            runCatching {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPoll >= 5_000) { lastPoll = now; poll() }
                advanceSurvey(now)
                publish()
            }.onFailure {
                state = state.copy(message = "Observation interrupted. Reconnect or restart capture.")
                stopSurveyInternal("Observation interrupted")
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        probesPaused = true
        instance = null
        if (receiverRegistered) unregisterReceiver(receiver)
        if (bound) unbindService(connection)
        worker.execute {
            stopSurveyInternal("Capture stopped")
            record("Capture stopped", "No observations are collected while capture is stopped.")
            history.close()
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun execute(action: () -> Unit) {
        if (!worker.isShutdown) worker.execute { runCatching(action).onFailure {
            state = state.copy(message = "Operation unavailable. Refresh the radio and try again.")
        }; publish() }
    }

    @Suppress("DEPRECATION")
    private fun bindMesh() {
        val version = runCatching { packageManager.getPackageInfo(PREFIX, 0).versionCode }.getOrNull()
        if (version != 29320069) {
            state = state.copy(message = "Meshtastic 2.7.13 is required.")
            return
        }
        bound = bindService(Intent().setClassName(PREFIX, "$PREFIX.service.MeshService"), connection, BIND_AUTO_CREATE)
        if (!bound) state = state.copy(message = "Open Meshtastic, connect the radio, then restart capture.")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) = execute {
            mesh = IMeshService.Stub.asInterface(binder); poll()
        }
        override fun onServiceDisconnected(name: ComponentName) = execute { mesh = null; disconnected() }
        override fun onBindingDied(name: ComponentName) = execute { mesh = null; disconnected() }
        override fun onNullBinding(name: ComponentName) = onBindingDied(name)
    }

    private fun disconnected() {
        if (state.connected) record("Connection gap", "Radio disconnected. Silence during this gap is not mesh inactivity.")
        stopSurveyInternal("Radio disconnected")
        nodes.replaceAll { _, n -> n.copy(acknowledged = 0, observed = 0, rssi = null, snr = null, hops = null) }
        radio = null
        state = state.copy(connected = false, message = "Radio disconnected. Reconnect in Meshtastic.")
    }

    private fun poll() {
        val api = mesh ?: return
        val current = runCatching { MeshChannelClient(api).read() }.getOrElse { disconnected(); return }
        val changed = ChannelProfile.digest(current.config.encode() + current.channels.encode()) + current.node
        if (fingerprint.isNotEmpty() && fingerprint != changed) {
            stopSurveyInternal("Radio or configuration changed")
            survey = null
            state = state.copy(surveyText = "Radio context changed. Run a new check for this configuration.")
            nodes.clear()
            record("Radio context changed", "Fresh observations are required for this radio configuration.")
        }
        if (!state.connected) record("Radio connected", "Metadata collection resumed. Cached nodes are not newly heard nodes.")
        fingerprint = changed
        radio = current
        val lora = current.config.lora
        val context = "${lora?.region} · ${lora?.modem_preset} · " +
            (if (lora?.override_frequency != null && lora.override_frequency != 0f) "${lora.override_frequency} MHz" else "slot ${lora?.channel_num ?: 0} (0 = automatic)") +
            " · ${lora?.hop_limit} hops"
        val cached = api.nodes.orEmpty()
        cached.take(1000).forEach { n ->
            val p = n.position?.takeIf { RadioEvidence.validPosition(it.latitude, it.longitude) }
            val old = nodes[n.num]
            nodes[n.num] = RadioNode(n.num, nodeId(n.num), cleanName(n.user?.longName ?: n.user?.shortName ?: nodeId(n.num)),
                n.channel, n.lastHeard.toLong() * 1000, p?.latitude, p?.longitude, p?.time?.toLong()?.times(1000),
                old?.observed ?: 0, old?.acknowledged ?: 0, old?.rssi, old?.snr, old?.hops,
                p?.precisionBits?.takeIf { it in 1..32 })
        }
        phoneFix?.takeIf { System.currentTimeMillis() - it.third in 0..120_000 }?.let { fix ->
            nodes[current.node]?.let { n -> nodes[current.node] = n.copy(latitude = fix.first, longitude = fix.second,
                positionTime = fix.third, positionPrecision = null, positionSource = "Phone GPS") }
        }
        val local = cached.firstOrNull { it.num == current.node }?.deviceMetrics
        state = state.copy(connected = true, message = "Radio connected · capture running", context = context, local = current.node,
            battery = local?.batteryLevel, utilization = local?.channelUtilization?.takeIf { it.isFinite() && it in 0f..100f },
            airtime = local?.airUtilTx?.takeIf { it.isFinite() && it in 0f..100f }, uptime = local?.uptimeSeconds,
            metricTime = local?.time?.toLong()?.times(1000) ?: 0)
    }

    @Suppress("DEPRECATION")
    private fun registerPackets() {
        val filter = IntentFilter().apply {
            PortNum.entries.forEach { addAction("$PREFIX.RECEIVED.${it.name}") }
            addAction("$PREFIX.MESSAGE_STATUS")
            addAction("$PREFIX.CONNECTION_CHANGED"); addAction("$PREFIX.NODE_CHANGE")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            // The upstream exported broadcast API is not an authenticated sender boundary.
            // Parse only bounded metadata; never persist the Parcelable or its body.
            runCatching {
                intent.setExtrasClassLoader(DataPacket::class.java.classLoader)
                when (intent.action) {
                    "$PREFIX.MESSAGE_STATUS" -> {
                        val id = intent.getIntExtra("$PREFIX.PacketId", 0)
                        val status = intent.getParcelableExtra<MessageStatus>("$PREFIX.Status")
                        if (status != null) execute { status(id, status.name) }
                    }
                    "$PREFIX.CONNECTION_CHANGED", "$PREFIX.NODE_CHANGE" -> execute { poll() }
                    else -> {
                        val p = intent.getParcelableExtra<DataPacket>("$PREFIX.Payload") ?: return
                        if ((p.bytes?.size ?: 0) > 4096) return
                        execute { receive(p) }
                    }
                }
            }.onFailure {
                execute {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastDecodeWarning >= 60_000) {
                        lastDecodeWarning = now
                        record("Packet metadata unavailable", "An incoming API event could not be decoded. The event body was discarded; it is not classified as a bad RF packet.")
                    }
                }
            }
        }
    }

    private fun receive(p: DataPacket) {
        if (!state.connected) return
        val now = System.currentTimeMillis()
        val source = p.from?.takeIf { it.matches(Regex("![0-9a-fA-F]{8}")) }?.lowercase() ?: return
        val key = "$source/${p.id}/${p.time}/${p.dataType}/${p.rssi}/${p.snr}"
        if (seen.containsKey(key) && now - seen.getValue(key) < 2000) return
        seen[key] = now
        while (seen.size > 1024) seen.remove(seen.keys.first())
        val number = source.drop(1).toLong(16).toInt()
        val local = number == state.local
        val mqtt = p.viaMqtt || p.transportMechanism == 5
        val rf = !local && !mqtt && (p.transportMechanism in 1..4 || p.rssi in -160..-1)
        val hops = if (rf) RadioEvidence.hops(p.hopStart, p.hopLimit) else null
        val node = nodes[number] ?: RadioNode(number, source, source, p.channel, 0)
        val own = nodes[state.local]
        val distance = if (node.latitude != null && node.longitude != null && own?.latitude != null && own.longitude != null)
            RadioEvidence.distance(own.latitude!!, own.longitude!!, node.latitude!!, node.longitude!!) else null
        val positionTime = if (distance != null && node.positionTime != null && own?.positionTime != null)
            minOf(node.positionTime!!, own.positionTime!!) else null
        val rssi = p.rssi.takeIf { rf && it in -160..-1 }
        val snr = p.snr.takeIf { rf && it.isFinite() && it in -40f..40f }
        if (rf) nodes[number] = node.copy(observed = now, rssi = rssi, snr = snr, hops = hops)
        var note = if (mqtt) "Internet-assisted observation; excluded from RF reachability." else if (rf) "Signal describes the final reception, not the entire path." else "Transport not verified as LoRa."
        if (p.dataType == PortNum.ROUTING_APP.value) {
            val reason = runCatching { p.bytes?.let { Routing.ADAPTER.decode(it).error_reason?.name } }.getOrNull()
            note += if (reason != null) " Routing: $reason. Request ID is not exposed in this packet event." else " Routing body could not be classified."
        }
        append(RadioEvent(now, if (local) "LOCAL" else "RX", kind(p.dataType), source,
            p.to?.take(32) ?: "Unknown", p.id, p.channel, p.bytes?.size ?: 0, rssi, snr, hops,
            p.hopLimit.takeIf { it in 0..7 }, p.relayNode?.takeIf { it in 1..255 }, mqtt, distance, positionTime,
            state.context, survey?.takeUnless { it.finished }?.id ?: "", "Observed", note,
            transport = if (mqtt) "MQTT" else if (rf) "LoRa" else "Local / unknown"))
    }

    private fun status(id: Int, raw: String) {
        if (id == 0) return
        val key = "$id/$raw"
        val now = System.currentTimeMillis()
        if (statusSeen.containsKey(key)) return
        statusSeen[key] = now
        while (statusSeen.size > 1024) statusSeen.remove(statusSeen.keys.first())
        val outcome = when (raw) {
            "RECEIVED" -> "Acknowledged"
            "DELIVERED" -> "Relay acknowledgment"
            "ERROR" -> "Failed"
            "ENROUTE" -> "Submitted to radio"
            "QUEUED" -> "Queued"
            else -> raw.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
        val s = survey
        val attempt = s?.attempts?.firstOrNull { it.packetId == id }
        val accepted = s?.status(id, outcome, SystemClock.elapsedRealtime()) == true
        if (accepted && outcome == "Acknowledged" && attempt != null) {
            nodes[attempt.node]?.let { nodes[attempt.node] = it.copy(acknowledged = now) }
        }
        append(RadioEvent(now, "STATUS", "Transmission update", destination = attempt?.node?.let(::nodeId) ?: "",
            packetId = id, context = state.context, survey = if (attempt != null) s.id else "", outcome = outcome,
            note = "Meshtastic status for a packet ID. DELIVERED can be an implicit routing acknowledgment; only RECEIVED identifies the destination in upstream status handling. Native diagnostic destinations are not retained by its chat database, so endpoint confirmation may be unavailable.",
            elapsed = if (accepted) attempt?.elapsed else null))
    }

    fun startSurvey(target: Int? = null) = execute {
        val now = SystemClock.elapsedRealtime()
        if (survey?.finished == false) return@execute
        if (now - lastSurveyEnd < 30_000) { state = state.copy(surveyText = "Wait 30 seconds between surveys."); return@execute }
        poll()
        if (!state.connected || radio == null) { state = state.copy(surveyText = "Connect the radio first."); return@execute }
        if (ProfileStore(this).switching()) { state = state.copy(surveyText = "Activation remains unconfirmed. Verify a profile before transmitting diagnostics."); return@execute }
        val candidates = RadioEvidence.ranked(nodes.values.filter { it.number != state.local && it.number != 0 && it.number != -1 && it.channel in 0..7 }, System.currentTimeMillis())
        val selected = if (target == null) candidates.take(4) else candidates.filter { it.number == target }.take(1)
        if (selected.isEmpty()) { state = state.copy(surveyText = "No addressable nodes in the radio cache yet. Listen on the intended mesh first."); return@execute }
        survey = RadioSurvey(UUID.randomUUID().toString().take(8), selected.map { it.number }, now, if (target == null) 2 else 4)
        probesPaused = false
        announcedResult = ""
        record("Survey started", "Addressed diagnostic checks only. No chat, position sharing, broadcasts, or configuration changes.")
    }

    fun stopSurvey(reason: String = "Stopped by you") {
        probesPaused = true
        execute { stopSurveyInternal(reason) }
    }
    private fun stopSurveyInternal(reason: String) {
        survey?.takeUnless { it.finished }?.stop(reason)
        finishSurvey()
    }

    private fun advanceSurvey(now: Long) {
        val s = survey ?: return
        if (s.finished) { finishSurvey(); return }
        if (stopping || probesPaused) { stopSurveyInternal("Survey paused"); return }
        if (!state.connected || ProfileStore(this).switching()) { stopSurveyInternal("Radio unavailable or activation in progress"); return }
        val busy = state.metricTime > 0 && System.currentTimeMillis() - state.metricTime in 0..120_000 && (state.utilization ?: 0f) >= 50f
        val before = s.attempts.map { it.outcome }
        val target = s.next(now, busy)
        s.attempts.forEachIndexed { i, a ->
            if (before.getOrNull(i) != a.outcome && a.outcome == "Unconfirmed")
                append(RadioEvent(System.currentTimeMillis(), "STATUS", "Check timed out", destination = nodeId(a.node),
                    packetId = a.packetId, context = state.context, survey = s.id, outcome = "Unconfirmed",
                    note = "No acknowledgment within 25 seconds. Firmware retries may still be in flight; late statuses remain visible."))
        }
        if (target != null) {
            val api = mesh ?: return
            // Recheck the full radio context immediately before each send, not only on the polling interval.
            poll()
            if (s.finished || !state.connected || stopping || probesPaused) return
            val current = radio ?: return
            val channel = nodes[target]?.channel ?: return
            val packet = DiagnosticProbe.packet(target, channel, api.packetId, current.config.lora?.hop_limit ?: 7)
            val telemetry = s.attempts.count { it.node == target } % 2 == 1
            s.sent(target, packet.id, now)
            append(RadioEvent(System.currentTimeMillis(), "TX", if (telemetry) "Telemetry request" else "Acknowledgment probe", nodeId(state.local), nodeId(target),
                packet.id, channel, if (telemetry) -1 else 1, context = state.context, survey = s.id, outcome = "Submitting",
                note = if (telemetry) "Native addressed device-metrics request. Response request IDs are not exposed; any subsequent reception remains separate evidence. Request byte length is not exposed." else "Addressed REPLY_APP diagnostic. Firmware may retry; one row is a submission, not every RF transmission."))
            try {
                if (telemetry) DiagnosticProbe.requestTelemetry(api, target, packet.id)
                else DiagnosticProbe.send(api, packet)
            } catch (_: Exception) {
                status(packet.id, "ERROR")
            }
        }
        state = state.copy(surveyText = if (busy) "Channel busy · waiting before the next check" else
            "${s.attempts.size}/${s.budget} checks started · ${s.attempts.count { it.outcome == "Acknowledged" }} acknowledged" +
                (s.pending?.let { " · waiting for ${nodes[it.node]?.name ?: nodeId(it.node)}" } ?: " · listening between checks"))
        finishSurvey()
    }

    private fun finishSurvey() {
        val s = survey ?: return
        if (!s.finished) return
        val observed = nodes.values.count { it.number in s.targets && it.observed >= state.started &&
            events.any { e -> e.survey == s.id && e.source == it.id && e.transport == "LoRa" } }
        val text = (s.stopped?.let { "$it. " } ?: "Survey complete. ") + s.brief() + " $observed selected nodes heard over RF during the survey."
        state = state.copy(surveyText = text)
        val signature = s.id + text
        if (announcedResult != signature) {
            if (announcedResult.isEmpty()) lastSurveyEnd = SystemClock.elapsedRealtime()
            announcedResult = signature
            record("Survey result", text)
        }
    }

    fun refreshRadio() = execute { poll() }
    fun phonePosition(latitude: Double, longitude: Double, time: Long) = execute {
        if (RadioEvidence.validPosition(latitude, longitude) && System.currentTimeMillis() - time in 0..120_000) {
            phoneFix = Triple(latitude, longitude, time)
            poll()
        }
    }
    fun markComparison() = execute { record("Comparison marker", "Antenna or location comparison begins here. Compare observations before and after this point.") }
    fun clearHistory() = execute { history.clear(); events.clear(); record("History cleared", "Previous metadata was removed from this device.") }

    private fun append(e: RadioEvent) {
        events.add(e)
        events.removeAll { it.time < System.currentTimeMillis() - 7 * 86_400_000L }
        while (events.size > 5000) events.removeAt(0)
        history.append(e)
    }
    private fun record(kind: String, note: String) = append(RadioEvent(System.currentTimeMillis(), "EVENT", kind,
        context = state.context, survey = survey?.id ?: "", note = note))
    private fun publish() {
        state = state.copy(nodes = nodes.values.toList(), events = events.toList(), surveyId = survey?.id ?: "",
            surveying = survey?.finished == false, attempts = survey?.attempts?.map { it.copy() } ?: emptyList(),
            surveyTargets = survey?.targets ?: emptyList())
    }

    private fun nodeId(number: Int) = "!" + number.toUInt().toString(16).padStart(8, '0')
    private fun cleanName(name: String) = name.filter { !it.isISOControl() }.take(48)
    private fun kind(port: Int) = when (port) {
        1 -> "Text message"
        3 -> "Position update"
        4 -> "Node information"
        5 -> "Routing / acknowledgment"
        6 -> "Administration"
        32 -> "Diagnostic reply port"
        67 -> "Telemetry"
        70 -> "Traceroute"
        71 -> "Neighbor information"
        256 -> "Private application data"
        else -> PortNum.fromValue(port)?.name?.removeSuffix("_APP")?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Port $port"
    }
}
