package com.example.spectrumanalyzer
object SonarGeometry {
    const val SPEED_OF_SOUND_M_S = 343.0 // ~20 C, dry air. Temperature-dependent.
    const val SPEAKER_MIC_DISTANCE_M = 0.0

    fun samplesToRange(deltaSamples: Double, sampleRate: Int): Double =
        (deltaSamples / sampleRate * SPEED_OF_SOUND_M_S + SPEAKER_MIC_DISTANCE_M) / 2.0

    fun rangeToSamples(rangeMeters: Double, sampleRate: Int): Int {
        val deltaPath = (2.0 * rangeMeters - SPEAKER_MIC_DISTANCE_M).coerceAtLeast(0.0)
        return (deltaPath / SPEED_OF_SOUND_M_S * sampleRate).toInt()
    }
}
