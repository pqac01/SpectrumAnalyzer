package com.example.spectrumanalyzer

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI

object ChirpGenerator {
    fun generate(
        sampleRate: Int = 48000,
        durationMs: Double = 28.0,
        startFreqHz: Double = 18000.0,
        endFreqHz: Double = 22000.0,
        tukeyAlpha: Double = 0.25,
        amplitude: Double = 0.9
    ): ShortArray {
        val sampleCount = (sampleRate * durationMs / 1000.0).toInt()
        val durationSec = sampleCount.toDouble() / sampleRate

        val chirp = ShortArray(sampleCount)
        val k = (endFreqHz - startFreqHz) / durationSec

        for (i in 0 until sampleCount) {
            val t = i.toDouble() / sampleRate

            val phase = 2.0 * PI * (startFreqHz * t + 0.5 * k * t * t)
            val sample = cos(phase)

            val window = tukeyWindow(i, sampleCount, tukeyAlpha)
            chirp[i] = (sample * window * amplitude * Short.MAX_VALUE).toInt().toShort()
        }

        return chirp
    }

    private fun tukeyWindow(i: Int, length: Int, alpha: Double): Double {
        if (alpha <= 0.0) return 1.0
        val a = alpha.coerceIn(0.0, 1.0)
        val n = i.toDouble()
        val nTotal = (length - 1).toDouble()
        val taperLen = a * nTotal / 2.0

        return when {
            n < taperLen -> 0.5 * (1.0 + cos(PI * (n / taperLen - 1.0)))
            n > nTotal - taperLen -> 0.5 * (1.0 + cos(PI * ((n - nTotal) / taperLen + 1.0)))
            else -> 1.0
        }.let { min(it, 1.0) }
    }
}
