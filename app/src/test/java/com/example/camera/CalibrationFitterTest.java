package com.example.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.example.camera.model.calibration.CalibrationFitter;
import com.example.camera.model.calibration.CalibrationSample;
import com.example.camera.model.calibration.CurveParams;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CalibrationFitterTest {

    @Test
    public void fitExpCurve_shouldRecoverKnownParameters() {
        double expectedA = 3.2;
        double expectedB = 0.61;
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double bv = -2.0 + i * 0.35;
            double l = expectedA * Math.exp(expectedB * bv);
            samples.add(new CalibrationSample(bv, l, 120.0, System.currentTimeMillis(), true));
        }

        CurveParams params = CalibrationFitter.fitExpCurve(samples, 8, 0.5);
        assertNotNull(params);
        assertEquals(expectedA, params.a, 0.01);
        assertEquals(expectedB, params.b, 0.01);
    }

    @Test
    public void fitExpCurve_shouldFailWhenSamplesInsufficient() {
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            samples.add(new CalibrationSample(-1 + i * 0.1, 20 + i, 100.0, System.currentTimeMillis(), true));
        }
        CurveParams params = CalibrationFitter.fitExpCurve(samples, 8, 0.5);
        assertNull(params);
    }

    @Test
    public void fitExpCurve_shouldFailWhenBvSpreadTooSmall() {
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double bv = -0.2 + i * 0.02;
            double l = 2.9 * Math.exp(0.729 * bv);
            samples.add(new CalibrationSample(bv, l, 100.0, System.currentTimeMillis(), true));
        }
        CurveParams params = CalibrationFitter.fitExpCurve(samples, 8, 0.5);
        assertNull(params);
    }
}
