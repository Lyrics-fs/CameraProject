package com.example.camera.model.calibration

import com.example.camera.model.calibration.math.MatrixBuilder
import com.example.camera.model.calibration.math.SvdSolver
import kotlin.jvm.JvmOverloads
import kotlin.math.ln
import java.util.LinkedHashSet
import kotlin.random.Random

private const val DEBEVEC_MAX_SAMPLE_TRIES_FACTOR = 200

/**
 * Debevec & Malik (1997) HDR radiance / camera response recovery via weighted least squares.
 *
 * Recovers the discrete response curve **g(Z)** such that, for pixel *i* and exposure *j*,
 * **g(Z_ij) ≈ ln(E_i) + ln(Δt_j)** in a weighted least-squares sense, with a second-difference
 * smoothness prior on **g** and a midpoint gauge **g(128) = 0** (implemented as a strongly
 * weighted row).
 *
 * @property[g] Recovered **g(z)** for each byte value **z ∈ [0,255]** (log irradiance up to affine
 *   ambiguity removed by the gauge).
 * @property[logIrradiance] **ln(E_i)** for each retained sample pixel **i** (same order as rows
 *   in the internal stacked system).
 * @property[rSquaredLogDeltaT] **R²** for predicting **ln(Δt_j)** from **g(Z_ij) − ln(E_i)**
 *   over all data rows (after optional monotonic projection on **g**).
 */
data class DebevecSolveResult(
    val g: DoubleArray,
    val logIrradiance: DoubleArray,
    val rSquaredLogDeltaT: Double,
)

/**
 * Weight on pixel value **Z** (triangle hat, peaks at **Z = 127**), as in Debevec & Malik (1997).
 *
 * Values **Z = 0** and **Z = 255** receive zero weight so saturated samples are de-emphasized;
 * callers should still exclude **Z = 0** and **Z = 255** from observations when building stacks.
 */
fun debevecWeight(z: Int): Double =
    if (z <= 127) z / 127.0 else (255.0 - z) / 128.0

/**
 * Solves the discrete Debevec–Malik camera response recovery problem for grayscale stacks.
 *
 * Input images must be **aligned** stacks of the same scene: each [ByteArray] is the same
 * length (e.g. **width × height** row-major), same ordering of pixels, and **exposureTimes[j]**
 * is the shutter time in **seconds** for **images[j]**.
 *
 * ## Algorithm (summary)
 * 1. Randomly sample pixel indices; keep pixels whose value is in **1…254** on **every** frame
 *    (drops under/over exposure).
 * 2. Build a weighted linear system in unknowns **g(0)…g(255)** and **ln(E_i)** for each kept
 *    pixel **i**, with data rows **√w(Z)(g(Z) − ln(E_i) − ln(Δt)) = 0**, smoothness rows on
 *    **√λ · (g(z−1) − 2g(z) + g(z+1))**, and a tight constraint row **g(128) = 0**.
 * 3. 用 [MatrixBuilder] 构造 **(A,b)**，[SvdSolver] 求 **min ||Ax − b||₂**。
 * 4. Project **g** to be **non-decreasing** in **Z** (simple rightward max pass).
 *
 * @param images Grayscale samples per exposure, **unsigned 0…255** stored in signed [Byte]s.
 * @param exposureTimes Exposure duration **Δt_j** in **seconds**, strictly positive, same length as [images].
 * @param lambda Smoothness strength **λ** (paper often uses **10…100**).
 * @param samplePixels Target count of valid spatial samples (may be slightly lower if random search fails).
 * @return **g[0…255]** — log-irradiance response at each discrete intensity; **not** including **ln(E)**.
 *
 * @throws IllegalArgumentException on invalid dimensions, non-positive exposures, or too few valid samples.
 */
class DebevecSolver {

    /**
     * @see debevecWeight
     */
    fun weight(z: Int): Double = debevecWeight(z)

    /**
     * @see DebevecSolver class KDoc
     */
    @JvmOverloads
    fun solve(
        images: List<ByteArray>,
        exposureTimes: List<Double>,
        lambda: Double = 10.0,
        samplePixels: Int = 256,
    ): DoubleArray = solveWithDiagnostics(images, exposureTimes, lambda, samplePixels).g.copyOf()

    /**
     * Same as [solve] but also returns per-pixel **ln(E)** and **R²** on **ln(Δt)** for validation.
     */
    @JvmOverloads
    fun solveWithDiagnostics(
        images: List<ByteArray>,
        exposureTimes: List<Double>,
        lambda: Double = 10.0,
        samplePixels: Int = 256,
    ): DebevecSolveResult {
        require(images.isNotEmpty()) { "images must not be empty" }
        require(images.size == exposureTimes.size) { "images.size (${images.size}) must match exposureTimes.size (${exposureTimes.size})" }
        require(samplePixels > 0) { "samplePixels must be positive" }
        require(lambda > 0.0) { "lambda must be positive" }
        exposureTimes.forEachIndexed { j, t ->
            require(t > 0.0) { "exposureTimes[$j] must be > 0, was $t" }
        }
        val pixelCount = images[0].size
        require(pixelCount > 0) { "image byte length must be positive" }
        for (idx in images.indices) {
            require(images[idx].size == pixelCount) { "images[$idx].size (${images[idx].size}) != ${pixelCount}" }
        }

        val n = images.size
        val rng = Random(42L)

        val validPixelIndices = sampleValidPixelIndices(images, samplePixels, rng, pixelCount)
        val m = validPixelIndices.size
        require(m >= 32) {
            "Too few valid pixels after saturation filter ($m). Need diverse exposures and unsaturated stack."
        }

        val zValues = Array(m) { ByteArray(n) }
        for (i in 0 until m) {
            val p = validPixelIndices[i]
            for (j in 0 until n) {
                zValues[i][j] = images[j][p]
            }
        }

        val zInt = Array(m) { i ->
            IntArray(n) { j -> zValues[i][j].toInt() and 0xFF }
        }
        val expArr = DoubleArray(n) { j -> exposureTimes[j] }
        val (a, b) = MatrixBuilder().buildDebevecMatrix(
            zInt,
            expArr,
            lambda,
            CONSTRAINT_SCALE,
        )
        val x = SvdSolver().solve(a, b)

        val lnT = DoubleArray(n) { j -> ln(exposureTimes[j]) }

        val g = DoubleArray(256) { k -> x[k] }
        enforceMonotoneNonDecreasing(g)

        // 单调投影会改变 g：联合解中的 ln(E) 不再最优；对每个像素用闭式平均重新估计 ln(E)。
        val logE = refitLogIrradianceGivenG(zValues, lnT, g)
        val r2 = computeRSquaredLogDeltaT(zValues, lnT, g, logE)

        return DebevecSolveResult(g = g, logIrradiance = logE, rSquaredLogDeltaT = r2)
    }

    companion object {
        /** Scale for the midpoint gauge row **g(128) = 0** (larger ⇒ harder constraint). */
        const val CONSTRAINT_SCALE: Double = MatrixBuilder.DEFAULT_CONSTRAINT_G128_SCALE
    }
}

private fun sampleValidPixelIndices(
    images: List<ByteArray>,
    targetCount: Int,
    rng: Random,
    pixelCount: Int,
): List<Int> {
    val n = images.size
    val found = LinkedHashSet<Int>(targetCount * 2)
    var tries = 0
    val maxTries = maxOf(targetCount * DEBEVEC_MAX_SAMPLE_TRIES_FACTOR, 4096)
    while (found.size < targetCount && tries < maxTries) {
        tries++
        val p = rng.nextInt(pixelCount)
        if (isValidPixelStack(images, p)) {
            found.add(p)
        }
    }
    require(found.size >= 32) {
        "Could not sample enough unsaturated pixels (got ${found.size} / $targetCount after $tries tries)"
    }
    return found.toList()
}

private fun isValidPixelStack(images: List<ByteArray>, pixelIndex: Int): Boolean {
    for (img in images) {
        val z = img[pixelIndex].toInt() and 0xFF
        if (z <= 0 || z >= 255) return false
    }
    return true
}

/**
 * 固定 **g** 后，对每个采样像素 **i** 用 **(1/N) Σ_j (g(Z_ij) − ln Δt_j)** 估计 **ln(E_i)**（该像素下
 * 各帧的最小二乘常数解）。
 */
private fun refitLogIrradianceGivenG(
    zValues: Array<ByteArray>,
    lnT: DoubleArray,
    g: DoubleArray,
): DoubleArray {
    val m = zValues.size
    val n = lnT.size
    val logE = DoubleArray(m)
    for (i in 0 until m) {
        var sum = 0.0
        for (j in 0 until n) {
            val z = zValues[i][j].toInt() and 0xFF
            sum += g[z] - lnT[j]
        }
        logE[i] = sum / n
    }
    return logE
}

private fun enforceMonotoneNonDecreasing(g: DoubleArray) {
    require(g.size == 256)
    for (z in 0 until 255) {
        if (g[z + 1] < g[z]) {
            g[z + 1] = g[z]
        }
    }
}

/**
 * **R²** for **ln(Δt_j)** using **ŷ = g(Z_ij) − ln(E_i)** vs observed **y = ln(Δt_j)** (constant per row).
 */
private fun computeRSquaredLogDeltaT(
    zValues: Array<ByteArray>,
    lnT: DoubleArray,
    g: DoubleArray,
    logE: DoubleArray,
): Double {
    var sumY = 0.0
    var count = 0
    val m = zValues.size
    val n = lnT.size
    for (i in 0 until m) {
        for (j in 0 until n) {
            sumY += lnT[j]
            count++
        }
    }
    val meanY = sumY / count
    var ssTot = 0.0
    var ssRes = 0.0
    for (i in 0 until m) {
        val lnEi = logE[i]
        for (j in 0 until n) {
            val z = zValues[i][j].toInt() and 0xFF
            val y = lnT[j]
            val yHat = g[z] - lnEi
            ssTot += (y - meanY) * (y - meanY)
            ssRes += (y - yHat) * (y - yHat)
        }
    }
    return if (ssTot <= 1e-18) {
        if (ssRes <= 1e-18) 1.0 else 0.0
    } else {
        1.0 - ssRes / ssTot
    }
}
