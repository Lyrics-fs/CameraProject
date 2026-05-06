package com.example.camera.presenter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ExposureSequenceCaptureTest {

    @Test
    public void generateExposureSequence_defaultTwelvePoints_firstIsMin() {
        List<Double> seq = ExposureSequenceCapture.generateExposureSequence(
                1.0 / 4000.0,
                1.0 / 15.0,
                12,
                1.0
        );
        assertEquals(12, seq.size());
        assertEquals(1.0 / 4000.0, seq.get(0), 1e-15);
        for (Double t : seq) {
            assertTrue(t >= 1.0 / 4000.0 - 1e-18);
            assertTrue(t <= 1.0 / 15.0 + 1e-12);
        }
    }

    @Test
    public void generateLogEvenlySpacedSequence_endpoints() {
        List<Double> seq = ExposureSequenceCapture.generateLogEvenlySpacedSequence(
                1.0 / 1000.0,
                1.0 / 30.0,
                8
        );
        assertEquals(8, seq.size());
        assertEquals(1.0 / 1000.0, seq.get(0), 1e-9);
        assertEquals(1.0 / 30.0, seq.get(7), 1e-6);
    }
}
