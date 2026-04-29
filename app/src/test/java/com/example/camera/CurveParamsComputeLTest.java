package com.example.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.camera.model.calibration.CurveParams;

import org.junit.Test;

public class CurveParamsComputeLTest {

    @Test
    public void bvFromDn_matchesLog2Normalized() {
        double bv128 = CurveParams.bvFromDn(128);
        assertEquals(Math.log(128.0 / 255.0) / Math.log(2.0), bv128, 1e-9);
    }

    @Test
    public void computeL_usesBvBridge() {
        CurveParams p = CurveParams.prior();
        double bv = CurveParams.bvFromDn(128);
        double expected = p.a * Math.exp(p.b * bv);
        assertEquals(expected, p.computeL(128), 1e-9);
    }

    @Test
    public void computeL_invalidParams_returnsNaN() {
        CurveParams p = new CurveParams(Double.NaN, 0.1, CurveParams.Source.CALIBRATED, 0L, 0.0);
        assertTrue(Double.isNaN(p.computeL(128)));
    }
}
