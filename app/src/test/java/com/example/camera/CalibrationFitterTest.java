package com.example.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        assertTrue(params.getRSquared() > 0.999);
        assertTrue(params.isHighQuality());
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

    @Test
    public void calculateRSquared_tooFewSamples_returnsZero() {
        List<CalibrationSample> samples = new ArrayList<>();
        samples.add(new CalibrationSample(0.0, 10.0, 100.0, 0L, true));
        assertEquals(0.0, CalibrationFitter.calculateRSquared(samples, 1.0, 0.5), 0.0);
    }

    @Test
    public void calculateRSquared_identicalL_returnsZero() {
        List<CalibrationSample> samples = new ArrayList<>();
        samples.add(new CalibrationSample(0.0, 42.0, 100.0, 0L, true));
        samples.add(new CalibrationSample(1.0, 42.0, 100.0, 0L, true));
        assertEquals(0.0, CalibrationFitter.calculateRSquared(samples, 1.0, 0.1), 0.0);
    }

    @Test
    public void fitExpCurve_removesHighRelativeErrorOutlierWhenR2Improves() {
        double a = 3.2;
        double b = 0.61;
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            double bv = -2.0 + i * 0.35;
            samples.add(new CalibrationSample(bv, a * Math.exp(b * bv), 120.0, 0L, true));
        }
        double bvBad = -2.0 + 5 * 0.35;
        double lGood = a * Math.exp(b * bvBad);
        samples.add(new CalibrationSample(bvBad, lGood * 0.5, 100.0, 0L, true));

        CalibrationFitter.FitResult fr = CalibrationFitter.fitExpCurveResult(samples, 8, 0.5);
        assertNotNull(fr);
        CurveParams params = fr.getCurveParams();
        assertTrue(params.getCalibrationOutliersRemoved() >= 1);
        assertEquals(9, fr.getSamplesUsed().size());
        assertTrue(params.getRSquared() > 0.99);
        assertEquals(a, params.a, 0.02);
        assertEquals(b, params.b, 0.02);
    }
}
