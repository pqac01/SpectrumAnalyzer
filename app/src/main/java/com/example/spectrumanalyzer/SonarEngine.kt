package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

class SonarEngine(
    private val sampleRate: Int = 48000,
    chirpDurationMs: Double = 28.0,
    startFreqHz: Double = 18000.0,
    endFreqHz: Double = 22000.0,
    chirpTukeyAlpha: Double = 1.0,
    private val recordWindowMs: Double = 500.0
) {
    companion object {
        private const val TAG = "SonarEngine"
        private const val MIN_DIRECT_SNR_DB = 15.0

        private const val CLIP_LEVEL = 32000
    }

    data class SonarResult(
        val success: Boolean,
        val echoes: List<Echo> = emptyList(),
        val errorMessage: String? = null,
        val audioSourceUsed: String = "N/A",
        val clientSilenced: Boolean? = null,
        val rawPeakAmplitude: Int = 0,
        val clippedSamples: Int = 0,
        val directPeakTimeMs: Double? = null,
        val directSnrDb: Double? = null,
        val profile: RangeProfile? = null
    )

    var debugWriter: PCMFileWriter? = null

    private val chirpPcm: ShortArray = ChirpGenerator.generate(
        sampleRate = sampleRate,
        durationMs = chirpDurationMs,
        startFreqHz = startFreqHz,
        endFreqHz = endFreqHz,
        tukeyAlpha = chirpTukeyAlpha
    )

    private val matchedFilter = MatchedFilter(chirpPcm)
    @SuppressLint("MissingPermission")
    fun measureOnce(maxRangeMeters: Double = 4.0, minRangeMeters: Double = 0.3): SonarResult {
        val recordSampleCount = (sampleRate * recordWindowMs / 1000.0).toInt()

        val raw = SonarAudio.buildRawAudioRecord(sampleRate, recordSampleCount)
            ?: return SonarResult(false, errorMessage = "AudioRecord init sikertelen", audioSourceUsed = "N/A (init failed)")
        val recorder = raw.record

        val track = SonarAudio.buildStaticTrack(sampleRate, chirpPcm, loop = false)
        val recorded = ShortArray(recordSampleCount)
        var filled = 0
        var silenced: Boolean? = null

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return SonarResult(
                    false,
                    errorMessage = "A felvétel nem indult el – lehet, hogy egy másik felvétel foglalja a mikrofont",
                    audioSourceUsed = raw.sourceLabel
                )
            }

            track.play()

            while (filled < recordSampleCount) {
                val n = recorder.read(recorded, filled, recordSampleCount - filled)
                if (n <= 0) break
                filled += n
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                silenced = recorder.activeRecordingConfiguration?.isClientSilenced
            }
        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
        }

        debugWriter?.let { writer ->
            try {
                writer.startFile(sampleRate)
                writer.appendData(recorded, filled)
                writer.closeFile()
            } catch (e: Exception) {
                Log.w(TAG, "debug PCM dump failed", e)
            }
        }

        var rawPeak = 0
        var clipped = 0
        for (i in 0 until filled) {
            val a = abs(recorded[i].toInt())
            if (a > rawPeak) rawPeak = a
            if (a >= CLIP_LEVEL) clipped++
        }

        val base = SonarResult(
            success = false,
            audioSourceUsed = raw.sourceLabel,
            clientSilenced = silenced,
            rawPeakAmplitude = rawPeak,
            clippedSamples = clipped
        )

        if (filled < recordSampleCount / 2) {
            return base.copy(errorMessage = "Túl kevés audio minta érkezett ($filled/$recordSampleCount)")
        }
        if (rawPeak == 0) {
            return base.copy(errorMessage = "Csak nullák jöttek a mikrofonról – a felvétel el van némítva (fut másik felvétel, pl. a spektrogram?)")
        }

        return processRecording(recorded, filled, maxRangeMeters, minRangeMeters, base)
    }

    private fun processRecording(
        recorded: ShortArray,
        validLength: Int,
        maxRangeMeters: Double,
        minRangeMeters: Double,
        base: SonarResult
    ): SonarResult {
        val lastValidLag = validLength - chirpPcm.size
        if (lastValidLag <= 0) {
            return base.copy(errorMessage = "A felvétel rövidebb, mint a chirp")
        }

        val envelope = matchedFilter.envelope(recorded, 0, validLength)

        var directIdx = 0
        var directVal = 0.0
        for (k in 0..lastValidLag) {
            if (envelope[k] > directVal) {
                directVal = envelope[k]
                directIdx = k
            }
        }

        val noiseLevel = MatchedFilter.median(envelope, 0, lastValidLag + 1)
        if (noiseLevel <= 0.0) {
            return base.copy(errorMessage = "Üres korreláció (nincs jel)")
        }

        val directSnrDb = 20.0 * log10(directVal / noiseLevel)
        val directTimeMs = directIdx * 1000.0 / sampleRate
        val withDirect = base.copy(directPeakTimeMs = directTimeMs, directSnrDb = directSnrDb)

        Log.i(TAG, "direct peak @ ${"%.1f".format(directTimeMs)} ms, SNR ${"%.1f".format(directSnrDb)} dB")

        if (directSnrDb < MIN_DIRECT_SNR_DB) {
            return withDirect.copy(
                errorMessage = "A chirp nem látszik egyértelműen a felvételben (SNR ${"%.0f".format(directSnrDb)} dB)"
            )
        }

        val maxRangeSamples = SonarGeometry.rangeToSamples(maxRangeMeters, sampleRate)
        if (directIdx + maxRangeSamples > lastValidLag) {
            return withDirect.copy(
                errorMessage = "A chirp túl későn érkezett az ablakban (${"%.0f".format(directTimeMs)} ms) – növeld a recordWindowMs-t"
            )
        }

        // Profile normalized to the direct peak (index 0 = direct peak).
        val profileLength = maxRangeSamples + 1
        val values = FloatArray(profileLength) { i ->
            (envelope[min(directIdx + i, lastValidLag)] / directVal).toFloat()
        }
        val normalizedNoise = (noiseLevel / directVal).toFloat()

        val echoes = EchoDetector.detect(
            values, normalizedNoise.toDouble(), sampleRate, minRangeMeters, maxRangeMeters
        )
        Log.i(TAG, "echoes: $echoes")

        return withDirect.copy(
            success = true,
            echoes = echoes,
            profile = RangeProfile(angleDeg = 0f, values = values, noiseLevel = normalizedNoise)
        )
    }
}
