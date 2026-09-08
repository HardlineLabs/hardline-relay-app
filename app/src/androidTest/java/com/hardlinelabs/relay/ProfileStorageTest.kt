package com.hardlinelabs.relay

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.net.Uri
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileStorageTest {
    @Test fun protectedQrSurvivesOfflineScanAndKeystoreStorageWithoutUnlocking() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "T" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        val settings = ChannelProvisioning.create(name)
        val pass = "fixture passphrase only".toCharArray()
        val original = ChannelProfile.create(settings, 37, pass)
        val image = BarcodeEncoder().encodeBitmap(original.qr(), BarcodeFormat.QR_CODE, 700, 700)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        // This fixture is an exact generated bitmap; optical detection is a separate hardware check.
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels))),
            mapOf(DecodeHintType.PURE_BARCODE to true)).text
        image.recycle(); pixels.fill(0)
        val scanned = ChannelProfile.parse(decoded)
        val store = ProfileStore(context)
        try {
            store.save(scanned)
            val restored = ProfileStore(context).list().first { it.id == original.id }
            assertTrue(restored.locked)
            assertEquals(settings, ChannelProfile.open(restored, pass).settings)
            assertThrows(Exception::class.java) { ChannelProfile.open(restored, "wrong password".toCharArray()) }
            context.contentResolver.query(Uri.parse("content://com.hardlinelabs.relay.profiles/profiles"), null, null, null, null)!!.use { cursor ->
                assertEquals(-1, cursor.getColumnIndex("psk"))
                assertEquals(-1, cursor.getColumnIndex("qr"))
                assertEquals(-1, cursor.getColumnIndex("password"))
            }
        } finally { store.remove(original.id); pass.fill('\u0000') }
    }
    @Test fun sharedProfilesKeepRadioActivationAndRemovalIndependent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = "profile-radio-test.v1"
        val store = ProfileStore(context, file)
        val pkg = ChannelProfile.create(ChannelProvisioning.create("Fixture"), 20, null)
        val open = ChannelProfile.open(pkg, null)
        try {
            store.save(pkg)
            store.selectRadio(1); store.activated(pkg, open, 1, 1)
            store.selectRadio(2)
            assertNull(store.active()); assertFalse(store.switching())
            assertEquals(pkg.id, store.list().single().id)
            store.clearActive(); assertTrue(store.switching())
            store.selectRadio(1)
            assertEquals(1, store.active()!!.getInt("node")); assertFalse(store.switching())
            store.selectRadio(2); assertTrue(store.switching())
            store.removalVerified(emptySet()); assertEquals(1, store.list().size)
            store.remove(pkg.id); assertTrue(store.list().isEmpty())
            store.selectRadio(1); assertNull(store.active())
        } finally { java.io.File(context.noBackupFilesDir, file).delete() }
    }
}
