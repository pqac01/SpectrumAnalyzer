package com.example.spectrumanalyzer

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class RangeProfile(
    val angleDeg: Float,
    val values: FloatArray,
    val noiseLevel: Float,
    val sampleIndex: Long = 0L
)

/** Render-ready polar image: per angle bin, intensity 0..255 per range cell (null = no data). */
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
        const val RAW_DISPLAY_RANGE_DB = 40.0

        const val CONTRAST_THRESHOLD_DB = 6.0

        const val CONTRAST_DISPLAY_SPAN_DB = 20.0

        private const val MIN_VISIBLE_INTENSITY = 64
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

    private fun averagedProfile(bin: Int): FloatArray? {
        val sum = sums[bin] ?: return null
        val n = counts[bin]
        return FloatArray(profileLength) { i -> sum[i] / n }
    }

    @Synchronized
    fun noiseLevel(bin: Int): Double =
        if (counts[bin] > 0) noiseSums[bin].toDouble() / counts[bin] else 0.0

    @Synchronized
    fun contrastDb(bin: Int): FloatArray? {
        val profile = averagedProfile(bin) ?: return null
        val noise = noiseLevel(bin).coerceAtLeast(1e-12)
        val median = leakage
        val echoFloor = noise * 10.0.pow(EchoDetector.ECHO_THRESHOLD_DB / 20.0)
        return FloatArray(profileLength) { i ->
            val v = profile[i].toDouble()
            if (median == null) {
                if (v > noise) (20.0 * log10(v / noise)).toFloat() else 0f
            } else {
                val reference = max(median[i].toDouble(), noise)
                val db = 20.0 * log10(max(v, 1e-12) / reference)
                if (v > echoFloor && db >= CONTRAST_THRESHOLD_DB) db.toFloat() else 0f
            }
        }
    }
    @Synchronized
    fun echoesAt(bin: Int): List<Echo> {
        val profile = averagedProfile(bin) ?: return emptyList()
        val searched = if (leakage == null) {
            profile
        } else {
            val contrast = contrastDb(bin) ?: return emptyList()
            FloatArray(profileLength) { i -> if (contrast[i] > 0f) profile[i] else 0f }
        }
        return EchoDetector.detect(searched, noiseLevel(bin), sampleRate, minRangeMeters, maxRangeMeters)
    }

    @Synchronized
    fun occupiedBinIndices(): List<Int> = (0 until binCount).filter { counts[it] > 0 }

    @Synchronized
    fun toImage(rangeCells: Int): PpiImage {
        val bins = arrayOfNulls<ByteArray>(binCount)
        if (profileLength > 0) {
            val active = leakage != null
            val minIndex = SonarGeometry.rangeToSamples(minRangeMeters, sampleRate)
            for (bin in 0 until binCount) {
                if (counts[bin] == 0) continue
                val contrast = contrastDb(bin) ?: continue
                val cells = ByteArray(rangeCells)
                for (c in 0 until rangeCells) {
                    val from = max(minIndex, c * profileLength / rangeCells)
                    val to = min(profileLength, max(from + 1, (c + 1) * profileLength / rangeCells))
                    var peakDb = 0f
                    for (i in from until to) if (contrast[i] > peakDb) peakDb = contrast[i]
                    val intensity = when {
                        !active -> peakDb / RAW_DISPLAY_RANGE_DB * 255.0
                        peakDb <= 0f -> 0.0
                        else -> MIN_VISIBLE_INTENSITY +
                                (peakDb - CONTRAST_THRESHOLD_DB) / CONTRAST_DISPLAY_SPAN_DB * (255 - MIN_VISIBLE_INTENSITY)
                    }
                    cells[c] = intensity.coerceIn(0.0, 255.0).toInt().toByte()
                }
                bins[bin] = cells
            }
        }
        return PpiImage(angleBinDeg, rangeCells, bins)
    }
}