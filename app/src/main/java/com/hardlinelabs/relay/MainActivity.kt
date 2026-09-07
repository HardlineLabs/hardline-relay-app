package com.hardlinelabs.relay

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.hardlinelabs.relay.core.RadioEvent
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.meshtastic.core.service.IMeshService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ink = Color.rgb(237, 242, 243)
    private val muted = Color.rgb(153, 171, 179)
    private val accent = Color.rgb(93, 218, 196)
    private lateinit var status: TextView
    private lateinit var savedList: LinearLayout
    private lateinit var radioList: LinearLayout
    private lateinit var store: ProfileStore
    private lateinit var observatory: ObservatoryUi
    private var pendingExport: List<RadioEvent> = emptyList()
    private var mapGpsRequested = false
    private val mapLocation = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.isFromMockProvider) return
            RadioMonitorService.instance?.phonePosition(location.latitude, location.longitude, location.time)
        }
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Deprecated("Legacy Android callback")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val controls = mutableListOf<Button>()
    private var service: IMeshService? = null
    private var bound = false
    private var busy = false
    private var snapshot: MeshChannelClient.Snapshot? = null
    private var secretDialog: AlertDialog? = null
    private var pendingId: String? = null
    private var lastTest = -5000L
    @Volatile private var unlockAfter = 0L

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(parent: LinearLayout, value: String, size: Float = 15f, color: Int = ink): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5)); parent.addView(this)
    }
    private fun card(parent: LinearLayout) = column().apply {
        background = GradientDrawable().apply { setColor(Color.rgb(25, 35, 42)); cornerRadius = dp(14).toFloat() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; minHeight = dp(48)
        setTextColor(ink); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(44, 67, 73))
        isEnabled = !busy; setOnClickListener { if (!busy) action() }; parent.addView(this, if (parent.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2)); controls.add(this)
    }
    private fun input(hintText: String, secret: Boolean = false) = EditText(this).apply {
        hint = hintText; isSingleLine = true; setTextColor(ink); setHintTextColor(muted)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 21, 27); window.navigationBarColor = Color.rgb(13, 21, 27)
        store = ProfileStore(this)
        // The plugin runs as ATAK's UID; its own manifest cannot change host visibility.
        runCatching {
            grantUriPermission("com.atakmap.app.civ", android.net.Uri.parse("content://com.hardlinelabs.relay.profiles/profiles"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        } // Relay also works independently when ATAK is not installed.
        val layout = column().apply { setPadding(dp(20), dp(24), dp(20), dp(24)); setBackgroundColor(Color.rgb(13, 21, 27)) }
        text(layout, "Private channels. Ready when you need them.", 14f, muted)
        val connectionCard = card(layout)
        status = text(connectionCard, "Connecting to radio…", 16f)
        button(connectionCard, "Refresh radio") { if (service == null) { disconnect(); bindMesh() } else refresh() }
        val actions = card(layout)
        button(actions, "Create channel") { createDialog() }
        button(actions, "Scan channel QR") {
            IntentIntegrator(this).setCaptureActivity(SecureScanActivity::class.java)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("Scan a Hardline channel")
                .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false).initiateScan()
        }
        text(layout, "SAVED CHANNELS", 12f, accent)
        savedList = column(); layout.addView(savedList)
        text(layout, "ON THIS RADIO", 12f, accent)
        radioList = column(); layout.addView(radioList)
        button(layout, "Open Meshtastic") { packageManager.getLaunchIntentForPackage("com.geeksville.mesh")?.let(::startActivity) }
        val atak = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(13,21,27)); addView(layout) }
        observatory = ObservatoryUi(this, atak, ::startCapture, ::exportMetadata, ::usePhoneGps)
        setContentView(observatory)
        pendingId = intent.getStringExtra("profileId")
        if (pendingId != null) observatory.showAtak()
        renderSaved(); bindMesh()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingId = intent.getStringExtra("profileId"); if (pendingId != null) observatory.showAtak(); refresh() }
    override fun onResume() { super.onResume(); if (::observatory.isInitialized) observatory.resume(); if (mapGpsRequested) usePhoneGps(); if (::savedList.isInitialized) { renderSaved(); if (service != null) refresh() } }
    override fun onPause() { if (::observatory.isInitialized) observatory.pause(); getSystemService(LocationManager::class.java).removeUpdates(mapLocation); super.onPause() }
    override fun onStop() { secretDialog?.dismiss(); secretDialog = null; super.onStop() }
    override fun onDestroy() { if (::observatory.isInitialized) observatory.destroy(); disconnect(); worker.shutdownNow(); super.onDestroy() }
    @Deprecated("Legacy Android back navigation")
    override fun onBackPressed() { if (!observatory.back()) super.onBackPressed() }
    private fun disconnect() { if (bound) unbindService(connection); bound = false; service = null }
    private fun startCapture() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (required.isNotEmpty()) { requestPermissions(required.toTypedArray(), 51); return }
        startForegroundService(Intent(this, RadioMonitorService::class.java))
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 51 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startCapture()
        if (requestCode == 53 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) usePhoneGps()
    }
    private fun usePhoneGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 53)
            return
        }
        mapGpsRequested = true
        getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000, 5f, mapLocation)
        Toast.makeText(this, "Waiting for phone GPS. Used only for the local map; never transmitted.", Toast.LENGTH_LONG).show()
    }
    private fun exportMetadata(events: List<RadioEvent>) {
        pendingExport = events.toList()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "relay-metadata.jsonl")
        }, 52)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IMeshService.Stub.asInterface(binder); refresh() }
        override fun onServiceDisconnected(name: ComponentName) { service = null; snapshot = null; status.text = "Radio unavailable. Reconnect in Meshtastic."; renderRadio() }
        override fun onBindingDied(name: ComponentName) { disconnect(); snapshot = null; status.text = "Connection lost. Tap Refresh radio." }
        override fun onNullBinding(name: ComponentName) = onBindingDied(name)
    }
    @Suppress("DEPRECATION")
    private fun bindMesh() {
        if (bound) return
        val version = runCatching { packageManager.getPackageInfo("com.geeksville.mesh", 0).versionCode }.getOrNull()
        if (version != 29320069) { status.text = "Install Meshtastic 2.7.13 to connect a radio. Saved channels work offline."; return }
        bound = runCatching { bindService(Intent().setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService"), connection, BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) status.text = "Open Meshtastic, then tap Refresh radio."
    }
    private fun progress(message: String) = runOnUiThread { if (!isDestroyed) status.text = message }
    private fun task(message: String, work: () -> String) {
        if (busy) return
        busy = true; controls.forEach { it.isEnabled = false }; status.text = message
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                controls.forEach { it.isEnabled = true }
                status.text = result.getOrElse { it.message ?: "Operation interrupted. Refresh and verify the radio." }
                renderSaved(); renderRadio()
                if (result.isSuccess) pendingId?.let { id ->
                    pendingId = null
                    runCatching { store.list().firstOrNull { it.id == id } }.getOrNull()?.let(::activateDialog)
                }
            }
        }
    }
    private fun refresh() {
        val s = service ?: return
        task("Checking radio…") {
            snapshot = null
            val current = MeshChannelClient(s).read(); snapshot = current
            "Radio connected · ${current.config.lora?.modem_preset} · ${current.config.lora?.hop_limit} hops"
        }
    }
    private fun createDialog() {
        val current = snapshot ?: run { status.text = "Connect and refresh the radio before creating a channel."; return }
        val layout = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val formError = text(layout, "", 13f, Color.rgb(255,151,141)).apply { visibility = View.GONE }
        val name = input("Channel name (1–11 characters)"); layout.addView(name)
        val publicMesh = Switch(this).apply { text = "Public-mesh compatible"; isChecked = true }; layout.addView(publicMesh)
        text(layout, "Both modes are encrypted. Separate frequency switches the whole radio; teammates must switch too.", 13f, muted)
        val protect = Switch(this).apply { text = "Protect with a passphrase" }; layout.addView(protect)
        val password = input("Passphrase (12+ characters)", true); layout.addView(password)
        val repeat = input("Repeat passphrase", true); layout.addView(repeat)
        password.visibility = View.GONE; repeat.visibility = View.GONE
        protect.setOnCheckedChangeListener { _, checked -> password.visibility = if (checked) View.VISIBLE else View.GONE; repeat.visibility = password.visibility }
        val dialog = AlertDialog.Builder(this).setTitle("Create private channel").setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save channel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                ChannelProfile.validateRadio(requireNotNull(current.config.lora))
                val settings = ChannelProvisioning.create(name.text.toString().trim())
                val pass = if (protect.isChecked) password.text.toString().toCharArray() else null
                val repeated = repeat.text.toString().toCharArray()
                val valid = pass == null || (pass.size in 12..128 && pass.contentEquals(repeated))
                repeated.fill('\u0000')
                if (!valid) { pass?.fill('\u0000'); password.error = "Use matching passphrases of 12–128 characters."; return@setOnClickListener }
                val slot = ChannelProfile.newSlot(publicMesh.isChecked)
                task("Saving encrypted channel…") {
                    try { store.save(ChannelProfile.create(settings, slot, pass)); "${settings.name} saved${if (pass != null) " · Locked" else ""}." }
                    finally { pass?.fill('\u0000') }
                }
                dialog.dismiss()
            } catch (e: Exception) { formError.visibility = View.VISIBLE; formError.text = e.message ?: "Check channel details." }
        } }
        dialog.setOnDismissListener { password.text.clear(); repeat.text.clear() }
        secretDialog = dialog; dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.show()
    }
    private fun activateDialog(pkg: ChannelProfile.Package) {
        val current = snapshot ?: run { status.text = "Connect the radio before activating ${pkg.name}."; return }
        if (SystemClock.elapsedRealtime() < unlockAfter) { status.text = "Wait a few seconds before trying again."; return }
        val layout = column().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        text(layout, "Activation installs this key on the radio. A different frequency leaves your current mesh.")
        val password = input("Channel passphrase", true)
        if (pkg.locked) layout.addView(password)
        val s = service ?: return
        val dialog = AlertDialog.Builder(this).setTitle("Activate ${pkg.name}").setView(layout)
            .setNegativeButton("Cancel", null).setPositiveButton("Activate", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            RadioMonitorService.instance?.stopSurvey("Profile activation requested")
            val pass = if (pkg.locked) password.text.toString().toCharArray() else null
            task("Unlocking ${pkg.name}…") {
                val opened = try { ChannelProfile.open(pkg, pass) }
                    catch (e: Exception) { unlockAfter = SystemClock.elapsedRealtime() + 5000; throw e }
                    finally { pass?.fill('\u0000') }
                store.clearActive()
                runOnUiThread { snapshot = null; renderSaved(); renderRadio() }
                progress("Pausing Relay traffic before changing the radio…")
                Thread.sleep(6000)
                val (index, after) = MeshChannelClient(s).activate(opened, current.node, ::progress)
                snapshot = after; store.activated(pkg, opened, after.node, index)
                "${pkg.name} active · ${if (opened.publicMesh) "Public-mesh compatible" else "Separate frequency"}."
            }
            dialog.dismiss()
        } }
        dialog.setOnDismissListener { password.text.clear() }
        secretDialog = dialog; dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.show()
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 52) {
            val events = pendingExport; pendingExport = emptyList()
            if (resultCode == RESULT_OK) data?.data?.let { uri -> worker.execute {
                val result = runCatching { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { out ->
                    events.forEach { out.write(ObservationStore.encode(it).toString()); out.newLine() }
                } }
                runOnUiThread { Toast.makeText(this, if (result.isSuccess) "Metadata exported" else "Export failed", Toast.LENGTH_LONG).show() }
            } }
            return
        }
        val content = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.contents ?: return
        task("Saving scanned channel…") {
            val pkg = ChannelProfile.parse(content)
            store.save(pkg)
            "${pkg.name} saved${if (pkg.locked) " · Locked" else ""}. No radio settings changed."
        }
    }
    private fun clearList(list: LinearLayout) {
        controls.removeAll { button -> var p = button.parent; while (p is View && p !== list) p = p.parent; p === list }
        list.removeAllViews()
    }
    private fun renderSaved() {
        clearList(savedList)
        try {
            val packages = store.list()
            if (packages.isEmpty()) text(savedList, "Create or scan a channel to prepare your contingencies.", 14f, muted)
            val active = store.active()
            packages.forEach { pkg ->
                val c = card(savedList)
                text(c, pkg.name, 20f).typeface = Typeface.DEFAULT_BOLD
                val installed = snapshot?.channels?.settings?.any { it.name == pkg.name } == true
                text(c, if (snapshot == null) "Saved · Radio state unverified" else if (installed) "Key installed on radio" else if (pkg.locked) "Locked · Not installed" else "Saved · Not installed", 13f, muted)
                val current = snapshot
                val isActive = active?.optString("id") == pkg.id && current != null && active.optInt("node") == current.node &&
                    current.config.lora?.channel_num == active.optInt("slot") && current.config.lora?.override_frequency == 0f &&
                    current.channels.settings.getOrNull(active.optInt("index"))?.let { ChannelProfile.digest(it.encode()) } == active.optString("fingerprint")
                if (isActive) text(c, if (active!!.optInt("slot") == 20) "Active · Public-mesh compatible" else "Active · Separate frequency", 13f, accent)
                val actions = LinearLayout(this); c.addView(actions)
                button(actions, if (pkg.locked && !installed) "Unlock" else "Activate") { activateDialog(pkg) }.contentDescription = if (pkg.locked && !installed) "Unlock & activate ${pkg.name}" else "Activate ${pkg.name}"
                button(actions, "Share QR") { showQr(pkg) }.contentDescription = "Share QR: ${pkg.name}"
                button(actions, "Remove") {
                    val currentRadio = snapshot
                    if (currentRadio == null) status.text = "Connect and refresh the radio before removing a channel."
                    else {
                        val indices = currentRadio.channels.settings.indices.filter { currentRadio.channels.settings[it].name.equals(pkg.name, true) }
                        when (indices.size) {
                            0 -> AlertDialog.Builder(this).setTitle("Remove saved ${pkg.name}?")
                                .setMessage("No installed channel with this name is listed on the connected radio. Remove this saved profile?")
                                .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                                    task("Removing saved profile…") { store.remove(pkg.id); "Saved profile removed; no matching radio channel was listed." }
                                }.show()
                            1 -> removeRadioDialog(indices.single(), currentRadio)
                            else -> status.text = "Multiple installed channels use this name. Choose the exact slot under Installed on radio."
                        }
                    }
                }.contentDescription = "Remove saved ${pkg.name}"
            }
        } catch (_: Exception) { text(savedList, "Saved channels cannot be unlocked on this phone. Existing data has been preserved.", 15f, Color.rgb(255, 151, 141)) }
    }
    private fun renderRadio() {
        clearList(radioList)
        val current = snapshot ?: run { text(radioList, "Radio unavailable", 14f, muted); return }
        current.channels.settings.forEachIndexed { index, c ->
            if (index == 0 || c != org.meshtastic.proto.ChannelSettings()) {
                val row = card(radioList); text(row, radioChannelName(current, index), 18f)
                text(row, "Slot $index · ${if (index == 0) "Primary" else "Secondary"}", 13f, muted)
                button(row, "Remove from radio") { removeRadioDialog(index, current) }
                if (index > 0 && runCatching { ChannelProvisioning.validate(c) }.isSuccess) {
                    button(row, "Send test: ${c.name}") {
                        val s = service ?: return@button
                        if (SystemClock.elapsedRealtime() - lastTest < 5000) { status.text = "Wait five seconds between tests."; return@button }
                        lastTest = SystemClock.elapsedRealtime()
                        task("Sending test…") { MeshChannelClient(s).send(index, c, current.node) }
                    }
                }
            }
        }
    }
    private fun radioChannelName(current: MeshChannelClient.Snapshot, index: Int) =
        current.channels.settings[index].name.ifBlank { current.config.lora?.modem_preset?.name?.replace('_', ' ') ?: "Unnamed channel" }
    private fun removeRadioDialog(index: Int, current: MeshChannelClient.Snapshot) {
        val name = radioChannelName(current, index)
        val saved = store.list().filter { it.name.equals(current.channels.settings[index].name, true) }
        val replacement = if (index == 0) current.channels.settings.indices.firstOrNull {
            it > 0 && current.channels.settings[it] != org.meshtastic.proto.ChannelSettings()
        } else null
        if (index == 0 && replacement == null) { status.text = "Install another channel before removing the primary channel."; return }
        val explanation = "Remove $name from radio slot $index? The radio will restart briefly so Relay can verify removal." +
            (replacement?.let { "\n\n${radioChannelName(current, it)} will become primary in slot 0; its former slot $it will be cleared." } ?: "") +
            if (saved.isNotEmpty()) "\n\nAlso deletes saved profile: ${saved.joinToString { it.name }}. This matches by name; inspect the selected slot before confirming." else ""
        AlertDialog.Builder(this).setTitle("Remove $name?").setMessage(explanation)
            .setNegativeButton("Cancel", null).setPositiveButton("Remove from radio") { _, _ ->
                val s = service ?: return@setPositiveButton
                RadioMonitorService.instance?.stopSurvey("Channel removal requested")
                task("Pausing traffic before removal…") {
                    store.clearActive()
                    runOnUiThread { snapshot = null; renderSaved(); renderRadio() }
                    Thread.sleep(6000)
                    val after = MeshChannelClient(s).remove(index, current, ::progress)
                    snapshot = after; store.removalVerified(saved.map { it.id }.toSet())
                    "$name removed from radio and matching saved profiles. Select a channel in the plugin to resume."
                }
            }.show()
    }
    private fun showQr(pkg: ChannelProfile.Package) {
        val bitmap = BarcodeEncoder().encodeBitmap(pkg.qr(), BarcodeFormat.QR_CODE, 700, 700)
        val image = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; contentDescription = "Channel provisioning QR" }
        secretDialog = AlertDialog.Builder(this).setTitle(pkg.name)
            .setMessage(if (pkg.locked) "Scan in Hardline Relay. Share the passphrase separately." else "This QR contains the channel key. Share only with teammates.")
            .setView(image).setPositiveButton("Done", null).create().also { dialog ->
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.setOnDismissListener { image.setImageDrawable(null); bitmap.recycle() }; dialog.show()
            }
    }
}
