package com.hardlinelabs.relay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hardlinelabs.relay.core.MeshState
import com.hardlinelabs.relay.core.meshStateFromService
import com.hardlinelabs.relay.core.statusLabel
import org.meshtastic.core.service.IMeshService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var service: IMeshService? = null
    private var bound = false
    private var active = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IMeshService.Stub.asInterface(binder)
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            status.text = statusLabel(MeshState.DISCONNECTED)
        }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply { text = "Hardline Relay"; textSize = 28f })
        status = TextView(this).apply { text = statusLabel(MeshState.DISCONNECTED); textSize = 20f }
        layout.addView(status)
        layout.addView(TextView(this).apply {
            text = "Development baseline • Meshtastic 2.7.13\n\nPair your radio in Meshtastic, then return here.\nChannel setup and secure QR sharing are coming next."
        })
        layout.addView(Button(this).apply {
            text = "Refresh radio status"
            setOnClickListener { if (bound) refresh() else bindMesh() }
        })
        layout.addView(Button(this).apply {
            text = "Open Meshtastic"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage("com.geeksville.mesh")?.let { startActivity(it) }
                    ?: run { status.text = "Install the pinned Meshtastic 2.7.13 APK first." }
            }
        })
        setContentView(layout)
    }

    override fun onStart() { super.onStart(); active = true; bindMesh() }
    override fun onStop() {
        active = false
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun bindMesh() {
        if (bound) return
        val version = try { packageManager.getPackageInfo("com.geeksville.mesh", 0).versionName }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        if (version == null || !Regex("^2\\.7\\.13(?:$|[ +(-])").containsMatchIn(version)) {
            status.text = "Meshtastic 2.7.13 required; found " + (version ?: "not installed")
            return
        }
        bound = try {
            bindService(Intent().setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService"),
                connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) { false }
        if (!bound) status.text = "Meshtastic service unavailable. Open Meshtastic and retry."
    }

    private fun refresh() {
        val current = service ?: return
        worker.execute {
            val label = try {
                statusLabel(meshStateFromService(current.connectionState()))
            } catch (_: android.os.RemoteException) { statusLabel(MeshState.DISCONNECTED) }
            runOnUiThread {
                if (active && service === current) {
                    status.text = label
                    android.util.Log.i("HardlineRelay", "Radio status refreshed")
                }
            }
        }
    }
}
