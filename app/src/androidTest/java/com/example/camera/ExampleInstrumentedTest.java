package com.example.camera;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {

    @Test
    public void appContext_packageName_matchesBuildConfig() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(BuildConfig.APPLICATION_ID, appContext.getPackageName());
    }

    @Test
    public void appContext_canResolveCoreViewIds() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotEquals(0, appContext.getResources().getIdentifier(
                "gl_surface_view", "id", appContext.getPackageName()));
        assertNotEquals(0, appContext.getResources().getIdentifier(
                "btn_retry", "id", appContext.getPackageName()));
    }
}