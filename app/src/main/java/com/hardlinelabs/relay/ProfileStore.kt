package com.hardlinelabs.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One local owner. Neither passphrases nor unwrapped protected profiles are saved. */
class ProfileStore(private val context: Context) {
    companion object { private val lock = Any(); private const val ALIAS = "hardline.profiles.v1" }
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "channels.v1"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject().put("profiles", JSONArray())
        val bytes = file.openRead().use { it.readBytes() }
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size))
        }
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    private fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val plain = value.toString().toByteArray(Charsets.UTF_8)
        val bytes = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val out = file.startWrite()
        try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    data class Snapshot(val profiles: List<ChannelProfile.Package>, val active: JSONObject?, val switching: Boolean)
    fun snapshot(): Snapshot = synchronized(lock) {
        val root = read()
        val array = root.getJSONArray("profiles")
        Snapshot((0 until array.length()).map { ChannelProfile.parse(array.getString(it)) },
            root.optJSONObject("active"), root.optBoolean("switching", false))
    }
    fun list() = snapshot().profiles
    fun save(pkg: ChannelProfile.Package) = synchronized(lock) {
        val root = read()
        val existing = root.getJSONArray("profiles")
        val packages = (0 until existing.length()).map { ChannelProfile.parse(existing.getString(it)) }
        if (packages.any { it.id == pkg.id }) return@synchronized
        require(packages.none { it.name.equals(pkg.name, true) }) { "A saved channel already uses this name. Nothing overwritten." }
        require(packages.size < 64) { "Saved channel limit reached. Remove an unused profile first." }
        existing.put(pkg.qr()); write(root)
    }
    fun remove(id: String) = synchronized(lock) {
        val root = read()
        val array = root.getJSONArray("profiles")
        root.put("profiles", JSONArray((0 until array.length()).map { array.getString(it) }
            .filter { ChannelProfile.parse(it).id != id }))
        if (root.optJSONObject("active")?.optString("id") == id) root.remove("active")
        write(root)
    }
    fun switching(): Boolean = synchronized(lock) { read().optBoolean("switching", false) }
    fun active(): JSONObject? = synchronized(lock) { read().optJSONObject("active") }
    fun activated(pkg: ChannelProfile.Package, opened: ChannelProfile.Open, node: Int, index: Int) = synchronized(lock) {
        val root = read()
        root.put("switching", false)
        root.put("active", JSONObject().put("id", pkg.id).put("name", pkg.name).put("node", node)
            .put("index", index).put("slot", opened.slot)
            .put("fingerprint", ChannelProfile.digest(opened.settings.encode()))
            .put("activation", java.util.UUID.randomUUID().toString()))
        write(root)
    }
    fun clearActive() = synchronized(lock) { val root = read(); root.remove("active"); root.put("switching", true); write(root) }
    fun removalVerified(ids: Set<String>) = synchronized(lock) {
        val root = read()
        val array = root.getJSONArray("profiles")
        root.put("profiles", JSONArray((0 until array.length()).map { array.getString(it) }
            .filter { ChannelProfile.parse(it).id !in ids }))
        root.remove("active"); root.put("switching", false); write(root)
    }
}
