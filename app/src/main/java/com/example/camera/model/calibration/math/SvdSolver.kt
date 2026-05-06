package com.example.camera.model.calibration.math

import org.apache.commons.math3.exception.MathIllegalArgumentException
import org.apache.commons.math3.exception.MathArithmeticException
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.ArrayRealVector
import org.apache.commons.math3.linear.SingularValueDecomposition

/**
 * 超定（或一般）线性方程组 **Ax ≈ b** 的 **最小二乘解**，基于 Apache Commons Math 的
 * [SingularValueDecomposition] 与内置 [DecompositionSolver]（与 **numpy.linalg.lstsq** 在良定系统上数值一致）。
 *
 * 典型用途：Debevec–Malik 离散化后的稠密 **A**（约 **3300×512**）。
 */
class SvdSolver {

    /**
     * 用 SVD 求 **min_x ||Ax − b||₂** 的解向量 **x**。
     *
     * @param a 稠密系数矩阵，**行 × 列**；每行长度须一致。
     * @param b 右端项，长度须等于 **a** 的行数。
     * @return **x**，长度等于 **a** 的列数。
     *
     * @throws IllegalArgumentException 维数不合法、或 Commons Math 认为矩阵非法。
     * @throws IllegalStateException 数值过程失败（例如完全秩亏且未收敛）。
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        require(a.isNotEmpty()) { "A must have at least one row" }
        val cols = a[0].size
        require(cols > 0) { "A must have at least one column" }
        for (row in a.indices) {
            require(a[row].size == cols) {
                "All rows of A must have length $cols (row $row has ${a[row].size})"
            }
        }
        require(b.size == a.size) {
            "b.size (${b.size}) must equal number of rows in A (${a.size})"
        }

        return try {
            val matrix = Array2DRowRealMatrix(a, false)
            val svd = SingularValueDecomposition(matrix)
            val xVec = svd.solver.solve(ArrayRealVector(b, false))
            xVec.toArray()
        } catch (e: MathIllegalArgumentException) {
            throw IllegalArgumentException("SVD setup failed: ${e.message}", e)
        } catch (e: MathArithmeticException) {
            throw IllegalStateException("SVD numerical failure: ${e.message}", e)
        }
    }

    companion object {
        /**
         * 便捷入口（Java 侧也可用 `SvdSolver.solve(a, b)` 静态调用）。
         */
        @JvmStatic
        fun solveStatic(a: Array<DoubleArray>, b: DoubleArray): DoubleArray =
            SvdSolver().solve(a, b)
    }
}
