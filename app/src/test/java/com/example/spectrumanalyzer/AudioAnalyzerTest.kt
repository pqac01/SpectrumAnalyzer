package com.example.spectrumanalyzer

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioAnalyzerTest {
    @Test
    fun `test FFT peak detection with 1000Hz sine wave`() {
        val sampleRate = 44100
        val bufferSize = 2048
        val targetFreq = 1000.0

        val testBuffer = ShortArray(bufferSize) { i ->
            (sin(2.0 * PI * targetFreq * i / sampleRate) * Short.MAX_VALUE).toInt().toShort()
        }

        var detectedPeakIndex = -1
        var maxMagnitude = -1f

        val analyzer = AudioAnalyzer(sampleRate, bufferSize) { magnitudes ->
            detectedPeakIndex = magnitudes.indices.drop(1).maxByOrNull { magnitudes[it] } ?: -1
            maxMagnitude = magnitudes[detectedPeakIndex]
        }

        analyzer.smoothingFactor = 1.0f

        analyzer.processAudioFrame(testBuffer, bufferSize, true)

        val expectedBin = (targetFreq * bufferSize / sampleRate).toInt()

        assertTrue("Detected peak ($detectedPeakIndex) out of range for expected bin ($expectedBin)",
            detectedPeakIndex in (expectedBin - 1)..(expectedBin + 1))

        assertTrue("The magnitude must be significant, but was: $maxMagnitude", maxMagnitude > 50f)
    }
}