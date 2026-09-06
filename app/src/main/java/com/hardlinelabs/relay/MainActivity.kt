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
        val layout = column().apply { setPadding(dp(20), dp(24), dp(20), dp(24)); setBackgroundColor(Color.rgb(13, 21, 27)) }
        text(layout, "HARDLINE LABS", 12f, accent).apply { letterSpacing = 0.18f; typeface = Typeface.DEFAULT_BOLD }
        text(layout, "Relay", 32f).typeface = Typeface.DEFAULT_BOLD
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
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Color.rgb(13,21,27)); addView(layout) })
        pendingId = intent.getStringExtra("profileId")
        renderSaved(); bindMesh()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingId = intent.getStringExtra("profileId"); refresh() }
    override fun onResume() { super.onResume(); if (::savedList.isInitialized) { renderSaved(); if (service != null) refresh() } }
    override fun onStop() { secretDialog?.dismiss(); secretDialog = null; super.onStop() }
    override fun onDestroy() { disconnect(); worker.shutdownNow(); super.onDestroy() }
    private fun disconnect() { if (bound) unbindService(connection); bound = false; service = null }
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
                    AlertDialog.Builder(this).setTitle("Remove saved ${pkg.name}?")
                        .setMessage("Removes the saved package from this phone. Installed radio keys remain; manage those in Meshtastic.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                            task("Removing saved channel…") { store.remove(pkg.id); "Saved channel removed." }
                        }.show()
                }.contentDescription = "Remove saved ${pkg.name}"
            }
        } catch (_: Exception) { text(savedList, "Saved channels cannot be unlocked on this phone. Existing data has been preserved.", 15f, Color.rgb(255, 151, 141)) }
    }
    private fun renderRadio() {
        clearList(radioList)
        val current = snapshot ?: run { text(radioList, "Radio unavailable", 14f, muted); return }
        current.channels.settings.forEachIndexed { index, c ->
            if (index == 0) text(radioList, if (c.name.isBlank()) "Primary channel" else "${c.name} · Primary", 14f, muted)
            else if (runCatching { ChannelProvisioning.validate(c) }.isSuccess) {
                val row = card(radioList); text(row, c.name, 18f)
                button(row, "Send test: ${c.name}") {
                    val s = service ?: return@button
                    if (SystemClock.elapsedRealtime() - lastTest < 5000) { status.text = "Wait five seconds between tests."; return@button }
                    lastTest = SystemClock.elapsedRealtime()
                    task("Sending test…") { MeshChannelClient(s).send(index, c, current.node) }
                }
            }
        }
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
