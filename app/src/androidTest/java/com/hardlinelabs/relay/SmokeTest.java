package com.hardlinelabs.relay;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SmokeTest {
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
