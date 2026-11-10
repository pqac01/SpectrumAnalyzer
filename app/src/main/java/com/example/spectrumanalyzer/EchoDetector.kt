package com.example.spectrumanalyzer

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class Echo(
    val distanceMeters: Double,
    val levelDb: Double
)

object EchoDetector {

    const val ECHO_THRESHOLD_DB = 12.0

    const val MAX_ECHOES = 5

    fun detect(
        profile: FloatArray,
        noiseLevel: Double,
        sampleRate: Int,
        minRangeMeters: Double,
        maxRangeMeters: Double,
        bandwidthHz: Double = 4000.0
    ): List<Echo> {
        if (noiseLevel <= 0.0 || profile.size < 3) return emptyList()

        val threshold = noiseLevel * 10.0.pow(ECHO_THRESHOLD_DB / 20.0)
        val from = max(1, SonarGeometry.rangeToSamples(minRangeMeters, sampleRate))
        val to = min(profile.size - 2, SonarGeometry.rangeToSamples(maxRangeMeters, sampleRate))

        val candidates = mutableListOf<Int>()
        for (k in from..to) {
            val v = profile[k]
            if (v > threshold && v >= profile[k - 1] && v > profile[k + 1]) {
                candidates.add(k)
            }
        }

        val minSeparation = max(1, (2.0 * sampleRate / bandwidthHz).toInt())
        val accepted = mutableListOf<Int>()
        for (k in candidates.sortedByDescending { profile[it] }) {
            if (accepted.none { abs(it - k) < minSeparation }) {
                accepted.add(k)
                if (accepted.size >= MAX_ECHOES) break
            }
        }

        return accepted.map { k ->
            Echo(
                distanceMeters = SonarGeometry.samplesToRange(k.toDouble(), sampleRate),
                levelDb = 20.0 * log10(profile[k] / noiseLevel)
            )
        }
    }
}
