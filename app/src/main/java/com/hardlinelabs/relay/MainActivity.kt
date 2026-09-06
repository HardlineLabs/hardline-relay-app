package com.hardlinelabs.relay

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.meshtastic.core.service.IMeshService
import org.meshtastic.proto.ChannelSettings
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var channelList: LinearLayout
    private lateinit var nameInput: EditText
    private val controls = mutableListOf<Button>()
    private val worker = Executors.newSingleThreadExecutor()
    private var service: IMeshService? = null
    private var bound = false
    private var active = false
    private var busy = false
    private var lastTestSentAt = 0L
    private var snapshot: MeshChannelClient.Snapshot? = null
    private var pendingImport: ChannelSettings? = null
    private var secretDialog: AlertDialog? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IMeshService.Stub.asInterface(binder)
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            clearChannels()
            status.text = "Meshtastic disconnected. Return here after reconnecting."
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
        }
        layout.addView(TextView(this).apply { text = "Hardline Relay"; textSize = 28f })
        layout.addView(TextView(this).apply {
            text = "Private channels • development build\nMeshtastic 2.7.13 / radio 2.7.15.567b8ea\nLongFast and existing radio settings are preserved."
        })
        status = TextView(this).apply { text = "Connecting to Meshtastic…"; textSize = 17f }
        layout.addView(status)
        button(layout, "Refresh radio & channels") {
            if (service != null) refresh() else {
                if (bound) unbindService(connection)
                bound = false
                bindMesh()
            }
        }
        nameInput = EditText(this).apply {
            hint = "New channel name (1–11 characters)"
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        layout.addView(nameInput)
        button(layout, "Create private channel") {
            try { confirmAdd(ChannelProvisioning.create(nameInput.text.toString().trim())) }
            catch (e: IllegalArgumentException) { status.text = e.message }
        }
        button(layout, "Scan channel QR") {
            IntentIntegrator(this).setCaptureActivity(SecureScanActivity::class.java)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan a Relay private-channel QR. It contains a secret.")
                .setBeepEnabled(false).setBarcodeImageEnabled(false)
                .setOrientationLocked(false).initiateScan()
        }
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(channelList)
        layout.addView(Button(this).apply {
            text = "Open Meshtastic"
            setOnClickListener { packageManager.getLaunchIntentForPackage("com.geeksville.mesh")?.let(::startActivity) }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun button(parent: LinearLayout, title: String, click: () -> Unit): Button = Button(this).apply {
        text = title
        isEnabled = !busy
        setOnClickListener { click() }
        parent.addView(this)
        controls.add(this)
    }

    private fun clearChannels() {
        snapshot = null
        controls.removeAll { it.parent === channelList }
        channelList.removeAllViews()
    }

    override fun onStart() { super.onStart(); active = true; bindMesh() }
    override fun onStop() {
        active = false
        secretDialog?.dismiss()
        secretDialog = null
        clearChannels()
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data) ?: return
        val content = result.contents ?: return
        try { pendingImport = ChannelProvisioning.decode(content) }
        catch (e: IllegalArgumentException) { status.text = e.message }
    }

    @Suppress("DEPRECATION")
    private fun bindMesh() {
        if (bound) return
        val version = try { packageManager.getPackageInfo("com.geeksville.mesh", 0).versionCode }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        if (version != 29320069) {
            status.text = "Install the pinned Meshtastic 2.7.13 (29320069) first."
            return
        }
        bound = try {
            bindService(Intent().setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService"),
                connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) { false }
        if (!bound) status.text = "Meshtastic service unavailable. Open Meshtastic and retry."
    }

    private fun runTask(message: String, task: (MeshChannelClient) -> Pair<String, MeshChannelClient.Snapshot?>) {
        val current = service ?: run { status.text = "Wait for Meshtastic connection."; return }
        if (busy) return
        busy = true
        controls.forEach { it.isEnabled = false }
        status.text = message
        worker.execute {
            val result = try { task(MeshChannelClient(current)) }
            catch (e: IllegalArgumentException) { (e.message ?: "Invalid channel.") to null }
            catch (e: IllegalStateException) { (e.message ?: "Operation unverified. Refresh before retrying.") to null }
            catch (_: Exception) { "Operation interrupted or unavailable. Refresh and inspect Meshtastic before retrying." to null }
            runOnUiThread {
                busy = false
                if (active && service === current) {
                    controls.forEach { it.isEnabled = true }
                    result.second?.let(::render)
                    status.text = result.first
                    pendingImport?.let { pendingImport = null; confirmAdd(it) }
                } else if (active && service != null) { refresh() }
            }
        }
    }

    private fun refresh() = runTask("Reading connected radio…") { client ->
        val current = client.read()
        "Radio connected • ${current.config.lora?.modem_preset} • ${current.config.lora?.hop_limit} hops" to current
    }

    private fun confirmAdd(settings: ChannelSettings) {
        val current = snapshot ?: run { status.text = "Refresh the connected radio first."; return }
        try { ChannelProvisioning.selectSlot(current.channels.settings, settings, current.maxChannels) }
        catch (e: Exception) { status.text = e.message; return }
        AlertDialog.Builder(this).setTitle("Add ${settings.name}?")
            .setMessage("AES-256 private secondary channel. Existing channels, region, modem preset and hop count stay unchanged. Both radios must use matching RF settings.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add channel") { _, _ ->
                runTask("Adding channel and requesting radio read-back…") { client ->
                    val (index, after) = client.add(settings, current.node)
                    "${settings.name} verified in slot $index. Share its QR with the other phone." to after
                }
            }.show()
    }

    private fun render(current: MeshChannelClient.Snapshot) {
        clearChannels()
        snapshot = current
        current.channels.settings.forEachIndexed { index, settings ->
            if (index == 0 || settings.name.isNotEmpty()) {
                channelList.addView(TextView(this).apply {
                    text = if (index == 0) "Primary channel — preserved" else "${settings.name} • slot $index"
                    textSize = 19f
                })
            }
            if (index > 0 && runCatching { ChannelProvisioning.validate(settings) }.isSuccess) {
                button(channelList, "Show QR: ${settings.name}") { showQr(settings) }
                button(channelList, "Send test: ${settings.name}") {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastTestSentAt < 5_000) {
                        status.text = "Wait five seconds between test messages to avoid accidental repeats."
                    } else {
                        lastTestSentAt = now
                        runTask("Submitting one test message…") { client ->
                            client.send(index, settings, current.node) to null
                        }
                    }
                }
            }
        }
    }

    private fun showQr(settings: ChannelSettings) {
        val bitmap = BarcodeEncoder().encodeBitmap(ChannelProvisioning.encode(settings), BarcodeFormat.QR_CODE, 640, 640)
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = "Private channel provisioning QR"
        }
        secretDialog = AlertDialog.Builder(this).setTitle("${settings.name} — secret QR")
            .setMessage("Anyone who scans this can join. Scan inside Relay on the other phone.")
            .setView(image).setPositiveButton("Hide", null).create().also { dialog ->
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.setOnDismissListener { image.setImageDrawable(null); bitmap.recycle(); secretDialog = null }
                dialog.show()
            }
    }
}
