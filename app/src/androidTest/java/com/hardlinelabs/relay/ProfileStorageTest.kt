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
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))).text
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
}
