package com.example.spectrumanalyzer

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

data class RangeProfile(
    val angleDeg: Float,
    val values: FloatArray,
    val noiseLevel: Float,
    val timestampNs: Long = 0L
)

class PpiImage(
    val angleBinDeg: Float,
    val rangeCells: Int,
    val bins: Array<ByteArray?>
)

class SonarScan(
    val angleBinDeg: Float,
    private val minBinsForLeakage: Int,
    private val sampleRate: Int,
    private val minRangeMeters: Double,
    private val maxRangeMeters: Double
) {
    companion object {
        const val DISPLAY_RANGE_DB = 40.0
    }

    val binCount = (360f / angleBinDeg).toInt()

    private val sums = arrayOfNulls<FloatArray>(binCount)
    private val counts = IntArray(binCount)
    private val noiseSums = FloatArray(binCount)
    private var leakage: FloatArray? = null
    private var profileLength = 0

    fun binOf(angleDeg: Float): Int {
        val a = ((angleDeg % 360f) + 360f) % 360f
        return min(binCount - 1, floor(a / angleBinDeg).toInt())
    }

    fun binCenterDeg(bin: Int): Float = (bin + 0.5f) * angleBinDeg

    @Synchronized
    fun add(profile: RangeProfile) {
        if (profileLength == 0) profileLength = profile.values.size
        if (profile.values.size != profileLength) return

        val bin = binOf(profile.angleDeg)
        val sum = sums[bin] ?: FloatArray(profileLength).also { sums[bin] = it }
        for (i in 0 until profileLength) sum[i] += profile.values[i]
        counts[bin]++
        noiseSums[bin] += profile.noiseLevel
    }

    @Synchronized
    fun clear() {
        sums.fill(null)
        counts.fill(0)
        noiseSums.fill(0f)
        leakage = null
        profileLength = 0
    }

    @Synchronized
    fun occupiedBins(): Int = counts.count { it > 0 }

    @Synchronized
    fun isLeakageActive(): Boolean = leakage != null

    @Synchronized
    fun recomputeLeakage() {
        val occupied = (0 until binCount).filter { counts[it] > 0 }
        if (occupied.size < minBinsForLeakage || profileLength == 0) {
            leakage = null
            return
        }
        val column = FloatArray(occupied.size)
        val result = FloatArray(profileLength)
        for (i in 0 until profileLength) {
            for ((j, bin) in occupied.withIndex()) {
                column[j] = sums[bin]!![i] / counts[bin]
            }
            column.sort()
            result[i] = column[column.size / 2]
        }
        leakage = result
    }

    @Synchronized
    fun cleanedProfile(bin: Int): FloatArray? {
        val sum = sums[bin] ?: return null
        val n = counts[bin]
        val l = leakage
        return FloatArray(profileLength) { i ->
            val v = sum[i] / n
            if (l != null) max(0f, v - l[i]) else v
        }
    }

    @Synchronized
    fun noiseLevel(bin: Int): Double =
        if (counts[bin] > 0) noiseSums[bin].toDouble() / counts[bin] else 0.0

    @Synchronized
    fun echoesAt(bin: Int): List<Echo> {
        val profile = cleanedProfile(bin) ?: return emptyList()
        return EchoDetector.detect(profile, noiseLevel(bin), sampleRate, minRangeMeters, maxRangeMeters)
    }

    @Synchronized
    fun occupiedBinIndices(): List<Int> = (0 until binCount).filter { counts[it] > 0 }

    @Synchronized
    fun toImage(rangeCells: Int): PpiImage {
        val bins = arrayOfNulls<ByteArray>(binCount)
        if (profileLength > 0) {
            val minIndex = SonarGeometry.rangeToSamples(minRangeMeters, sampleRate)
            for (bin in 0 until binCount) {
                if (counts[bin] == 0) continue
                val profile = cleanedProfile(bin) ?: continue
                val noise = noiseLevel(bin).coerceAtLeast(1e-12)
                val cells = ByteArray(rangeCells)
                for (c in 0 until rangeCells) {
                    val from = max(minIndex, c * profileLength / rangeCells)
                    val to = min(profileLength, max(from + 1, (c + 1) * profileLength / rangeCells))
                    var peak = 0f
                    for (i in from until to) if (profile[i] > peak) peak = profile[i]
                    val db = if (peak > 0f) 20.0 * log10(peak / noise) else 0.0
                    cells[c] = (db / DISPLAY_RANGE_DB * 255.0).coerceIn(0.0, 255.0).toInt().toByte()
                }
                bins[bin] = cells
            }
        }
        return PpiImage(angleBinDeg, rangeCells, bins)
    }
}
