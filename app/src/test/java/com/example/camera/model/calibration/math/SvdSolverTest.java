package com.example.camera.model.calibration.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Random;

/**
 * {@link SvdSolver} 与稠密最小二乘；大矩阵规模与耗时验收。
 */
public class SvdSolverTest {

    @Test
    public void solve_smallSquare_identity() {
        double[][] a = {
                {1.0, 0.0},
                {0.0, 1.0},
        };
        double[] b = {3.0, -2.0};
        double[] x = new SvdSolver().solve(a, b);
        assertEquals(2, x.length);
        assertEquals(3.0, x[0], 1e-9);
        assertEquals(-2.0, x[1], 1e-9);
    }

    @Test
    public void solve_overdetermined_recoverKnownX_noiseless() {
        // A: 40 x 6 full column rank (random fixed seed), x_known, b = A * x_known
        int rows = 40;
        int cols = 6;
        Random rng = new Random(20260205L);
        double[][] a = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                a[i][j] = rng.nextGaussian();
            }
        }
        double[] xKnown = new double[cols];
        for (int j = 0; j < cols; j++) {
            xKnown[j] = rng.nextGaussian();
        }
        double[] b = matVec(a, xKnown);

        double[] x = new SvdSolver().solve(a, b);
        assertEquals(cols, x.length);
        for (int j = 0; j < cols; j++) {
            assertEquals("j=" + j, xKnown[j], x[j], 1e-6);
        }
    }

    @Test
    public void solve_largeTallMatrix_underFiveSeconds() {
        int rows = 1000;
        int cols = 300;
        Random rng = new Random(42L);
        double[][] a = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                a[i][j] = rng.nextGaussian() * 0.5 + 0.1;
            }
        }
        double[] xKnown = new double[cols];
        for (int j = 0; j < cols; j++) {
            xKnown[j] = rng.nextGaussian();
        }
        double[] b = matVec(a, xKnown);

        long t0 = System.currentTimeMillis();
        double[] x = new SvdSolver().solve(a, b);
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue("SVD solve took " + elapsed + "ms (expected < 5000ms on typical desktop/CI)", elapsed < 5000L);

        assertEquals(cols, x.length);
        double[] residual = subtract(matVec(a, x), b);
        double rmse = l2Norm(residual) / Math.sqrt(rows);
        assertTrue("RMSE of residual too large: " + rmse, rmse < 1e-5);
    }

    @Test
    public void solve_dimensionMismatch_throws() {
        double[][] a = {{1, 2}, {3, 4}};
        double[] b = {1.0};
        try {
            new SvdSolver().solve(a, b);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("b.size"));
        }
    }

    private static double[] matVec(double[][] a, double[] x) {
        int rows = a.length;
        int cols = x.length;
        double[] b = new double[rows];
        for (int i = 0; i < rows; i++) {
            double s = 0.0;
            for (int j = 0; j < cols; j++) {
                s += a[i][j] * x[j];
            }
            b[i] = s;
        }
        return b;
    }

    private static double[] subtract(double[] u, double[] v) {
        double[] o = new double[u.length];
        for (int i = 0; i < u.length; i++) {
            o[i] = u[i] - v[i];
        }
        return o;
    }

    private static double l2Norm(double[] v) {
        double s = 0.0;
        for (double x : v) {
            s += x * x;
        }
        return Math.sqrt(s);
    }

}
