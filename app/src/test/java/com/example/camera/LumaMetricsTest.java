package com.example.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.camera.model.calibration.LumaMetrics;

import org.junit.Test;

public class LumaMetricsTest {

    @Test
    public void bvFromDn_matchesLog2Normalized() {
        double bv128 = LumaMetrics.bvFromDn(128);
        assertEquals(Math.log(128.0 / 255.0) / Math.log(2.0), bv128, 1e-9);
    }

    @Test
    public void bvFromDn_nonFinite_returnsNaN() {
        assertTrue(Double.isNaN(LumaMetrics.bvFromDn(Double.NaN)));
    }
}
