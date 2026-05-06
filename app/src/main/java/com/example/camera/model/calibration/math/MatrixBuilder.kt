package com.example.camera.model.calibration.math

import com.example.camera.model.calibration.debevecWeight
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 构造 Debevec & Malik (1997) 离散化后的加权最小二乘稠密矩阵 **A** 与右端 **b**。
 *
 * 未知数顺序：**[g(0)…g(255), ln(E_0)…ln(E_{M−1})]**，共 **256 + M** 维。
 *
 * 方程组成：
 * - **M×N** 条数据行：**√w(Z_ij)·g(Z_ij) − √w·ln(E_i) = √w·ln(Δt_j)**
 * - **254** 条平滑行：**√λ·(g(z−1) − 2g(z) + g(z+1)) = 0**，**z = 1…254**
 * - **1** 条锚定行：**S·g(128) = 0**（**S = constraintG128Scale**，近似 **g(128)=0**）
 */
class MatrixBuilder {

    /**
     * @param zValues 采样像素 **i** 在曝光 **j** 下的灰度 **Z_ij ∈ [0,255]**（建议已排除 0/255）。
     * @param exposureTimes 各帧曝光 **Δt_j**（秒），长度 **N**，须为正。
     * @param lambda 平滑强度 **λ**。
     * @param constraintG128Scale **g(128)=0** 约束行的系数尺度（越大越接近硬约束）。
     * @return **Pair(A, b)**，**A** 为 **(M·N + 254 + 1) × (256 + M)**，**b** 长度与行数相同。
     */
    fun buildDebevecMatrix(
        zValues: Array<IntArray>,
        exposureTimes: DoubleArray,
        lambda: Double = 10.0,
        constraintG128Scale: Double = DEFAULT_CONSTRAINT_G128_SCALE,
    ): Pair<Array<DoubleArray>, DoubleArray> {
        val m = zValues.size
        require(m > 0) { "zValues must be non-empty" }
        val n = exposureTimes.size
        require(n > 0) { "exposureTimes must be non-empty" }
        require(lambda > 0.0) { "lambda must be positive" }
        exposureTimes.forEachIndexed { j, t ->
            require(t > 0.0) { "exposureTimes[$j] must be > 0, was $t" }
        }
        for (i in zValues.indices) {
            require(zValues[i].size == n) {
                "zValues[$i].size (${zValues[i].size}) must equal exposure count $n"
            }
        }

        val dataRows = m * n
        val smoothRows = 254
        val constraintRows = 1
        val rows = dataRows + smoothRows + constraintRows
        val cols = 256 + m

        val a = Array(rows) { DoubleArray(cols) }
        val b = DoubleArray(rows)
        var r = 0

        val lnT = DoubleArray(n) { j -> ln(exposureTimes[j]) }
        val sqrtLambda = sqrt(lambda)

        for (i in 0 until m) {
            for (j in 0 until n) {
                val z = zValues[i][j].coerceIn(0, 255)
                val w = debevecWeight(z)
                val sw = sqrt(w)
                a[r][z] = sw
                a[r][256 + i] = -sw
                b[r] = sw * lnT[j]
                r++
            }
        }

        for (z in 1..254) {
            a[r][z - 1] = sqrtLambda
            a[r][z] = -2.0 * sqrtLambda
            a[r][z + 1] = sqrtLambda
            b[r] = 0.0
            r++
        }

        a[r][128] = constraintG128Scale
        b[r] = 0.0
        r++

        require(r == rows) { "internal row count mismatch: r=$r rows=$rows" }
        return Pair(a, b)
    }

    companion object {
        /** 与 [com.example.camera.model.calibration.DebevecSolver] 默认锚定强度一致。 */
        const val DEFAULT_CONSTRAINT_G128_SCALE: Double = 1_000_000.0
    }
}
