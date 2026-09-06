package com.hardlinelabs.relay;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SmokeTest {
    @Test public void scannerRuntimeAndQrEncodingAreAvailable() throws Exception {
        assertNotNull(Class.forName("androidx.core.content.ContextCompat"));
        assertNotNull(Class.forName("com.journeyapps.barcodescanner.CaptureManager"));
        android.graphics.Bitmap image = new com.journeyapps.barcodescanner.BarcodeEncoder()
            .encodeBitmap("non-secret scanner smoke test", com.google.zxing.BarcodeFormat.QR_CODE, 256, 256);
        assertEquals(256, image.getWidth());
        image.recycle();
    }

    @Test public void startsWithoutHardware() {
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(screen -> {
                assertEquals("com.hardlinelabs.relay", screen.getPackageName());
                assertNotNull(screen.findViewById(android.R.id.content));
            });
            activity.recreate();
            activity.onActivity(screen -> assertFalse(screen.isFinishing()));
        }
    }
}
