package com.example.camera.model.calibration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Unit tests for {@link DebevecSolver}: synthetic linear stacks (affine-in-log radiance model)
 * and a monotone log-shaped discrete response (S-curve in Z).
 */
public class DebevecSolverTest {

    @Test
    public void solve_linearResponse_rSquaredAtLeast099() {
        int pixels = 40 * 40;
        int nExp = 8;
        double alpha = 0.07;
        List<Double> times = geometricExposureTimesSeconds(nExp, 0.015625);
        List<byte[]> images = synthesizeLinearGStack(pixels, nExp, times, alpha, 777L);

        DebevecSolver solver = new DebevecSolver();
        DebevecSolveResult result = solver.solveWithDiagnostics(images, times, 50.0, 160);

        assertTrue("R² on ln(Δt) should be ≥ 0.99 for noiseless synthetic stack: " + result.getRSquaredLogDeltaT(),
                result.getRSquaredLogDeltaT() >= 0.99);

        double[] gHat = result.getG();
        double[] gTrue = linearGTrue(alpha);
        double corr = pearsonCorrelation(gHat, gTrue);
        assertTrue("Recovered g should align with true linear g (r > 0.995): r=" + corr, corr > 0.995);
    }

    @Test
    public void solve_logShapedResponse_rSquaredAtLeast099AndMonotone() {
        int pixels = 40 * 40;
        int nExp = 8;
        List<Double> times = geometricExposureTimesSeconds(nExp, 0.01);
        List<byte[]> images = synthesizeKnownGStack(pixels, nExp, times, DebevecSolverTest::logShapedG, 9001L);

        DebevecSolver solver = new DebevecSolver();
        DebevecSolveResult result = solver.solveWithDiagnostics(images, times, 80.0, 240);

        // 离散 Z = argmin|g(Z)−logRad| 带来量化误差，R² 通常仍很高但未必 ≥0.99
        assertTrue("R² on ln(Δt) should be strong fit: " + result.getRSquaredLogDeltaT(),
                result.getRSquaredLogDeltaT() >= 0.93);

        double[] g = result.getG();
        assertMonotoneNonDecreasing(g, 1e-7);
    }

    @Test
    public void solve_eightFrameStack_matchesPublicApi() {
        int pixels = 32 * 32;
        int nExp = 8;
        List<Double> times = geometricExposureTimesSeconds(nExp, 0.02);
        List<byte[]> images = synthesizeLinearGStack(pixels, nExp, times, 0.065, 4242L);

        DebevecSolver solver = new DebevecSolver();
        double[] gOnly = solver.solve(images, times, 40.0, 120);
        DebevecSolveResult full = solver.solveWithDiagnostics(images, times, 40.0, 120);

        assertEquals(256, gOnly.length);
        for (int z = 0; z < 256; z++) {
            assertEquals(full.getG()[z], gOnly[z], 1e-12);
        }
    }

    private static void assertMonotoneNonDecreasing(double[] g, double tol) {
        for (int z = 0; z < 255; z++) {
            assertTrue("g[" + z + "] should be ≤ g[" + (z + 1) + "]", g[z + 1] + tol >= g[z]);
        }
    }

    private static double[] linearGTrue(double alpha) {
        double[] g = new double[256];
        for (int z = 0; z < 256; z++) {
            g[z] = alpha * (z - 128.0);
        }
        return g;
    }

    private static double logShapedG(int z) {
        return Math.log((z + 1.0) / 129.0);
    }

    private static List<Double> geometricExposureTimesSeconds(int n, double t0) {
        List<Double> times = new ArrayList<>(n);
        for (int j = 0; j < n; j++) {
            times.add(t0 * Math.pow(2.0, j));
        }
        return times;
    }

    /**
     * Stack where true discrete g is linear: g(z) = alpha * (z - 128), ln radiance = ln E + ln t.
     */
    private static List<byte[]> synthesizeLinearGStack(
            int pixels, int nExp, List<Double> times, double alpha, long seed) {
        Random r = new Random(seed);
        List<byte[]> images = new ArrayList<>(nExp);
        for (int j = 0; j < nExp; j++) {
            images.add(new byte[pixels]);
        }
        for (int p = 0; p < pixels; p++) {
            double logE = r.nextDouble() * 0.9 - 0.45;
            for (int j = 0; j < nExp; j++) {
                double logRad = logE + Math.log(times.get(j));
                double zIdeal = 128.0 + logRad / alpha;
                int z = (int) Math.round(zIdeal);
                if (z < 1) {
                    z = 1;
                }
                if (z > 254) {
                    z = 254;
                }
                images.get(j)[p] = (byte) (z & 0xFF);
            }
        }
        return images;
    }

    private interface DiscreteG {
        double g(int z);
    }

    private static List<byte[]> synthesizeKnownGStack(
            int pixels, int nExp, List<Double> times, DiscreteG gTrue, long seed) {
        Random r = new Random(seed);
        List<byte[]> images = new ArrayList<>(nExp);
        for (int j = 0; j < nExp; j++) {
            images.add(new byte[pixels]);
        }
        for (int p = 0; p < pixels; p++) {
            double logE = r.nextDouble() * 1.0 - 0.5;
            for (int j = 0; j < nExp; j++) {
                double logRad = logE + Math.log(times.get(j));
                int z = nearestZForLogRad(gTrue, logRad);
                images.get(j)[p] = (byte) (z & 0xFF);
            }
        }
        return images;
    }

    /** Pick z in 1…254 minimizing |g(z) - logRad| on the discrete lattice. */
    private static int nearestZForLogRad(DiscreteG gTrue, double logRad) {
        int bestZ = 128;
        double bestErr = Double.MAX_VALUE;
        for (int z = 1; z <= 254; z++) {
            double err = Math.abs(gTrue.g(z) - logRad);
            if (err < bestErr) {
                bestErr = err;
                bestZ = z;
            }
        }
        return bestZ;
    }

    private static double pearsonCorrelation(double[] x, double[] y) {
        int n = x.length;
        double mx = 0.0;
        double my = 0.0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxx = 0.0;
        double syy = 0.0;
        double sxy = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        if (sxx <= 1e-18 || syy <= 1e-18) {
            return sxy == 0 ? 1.0 : 0.0;
        }
        return sxy / Math.sqrt(sxx * syy);
    }
}
