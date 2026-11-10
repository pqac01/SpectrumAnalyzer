package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class SonarEngine(
    private val sampleRate: Int = 48000,
    private val chirpDurationMs: Double = 28.0,
    private val startFreqHz: Double = 18000.0,
    private val endFreqHz: Double = 22000.0,

    private val chirpTukeyAlpha: Double = 1.0,

    private val recordWindowMs: Double = 500.0,
    private val speakerMicDistanceMeters: Double = 0.0
) {
    companion object {
        const val SPEED_OF_SOUND_M_S = 343.0
        private const val TAG = "SonarEngine"
        private const val MIN_DIRECT_SNR_DB = 15.0
        private const val ECHO_THRESHOLD_DB = 12.0
        private const val MAX_ECHOES = 5
        private const val CLIP_LEVEL = 32000
    }

    data class Echo(
        val distanceMeters: Double,
        val levelDb: Double
    )

    data class SonarResult(
        val success: Boolean,
        val echoes: List<Echo> = emptyList(),
        val primaryDistanceMeters: Double? = null,
        val errorMessage: String? = null,
        val audioSourceUsed: String = "N/A",
        val clientSilenced: Boolean? = null,
        val rawPeakAmplitude: Int = 0,
        val clippedSamples: Int = 0,
        val directPeakTimeMs: Double? = null,
        val directSnrDb: Double? = null
    )

    var debugWriter: PCMFileWriter? = null

    private var lastAudioSourceUsed: String = "N/A"

    private val chirpPcm: ShortArray = ChirpGenerator.generate(
        sampleRate = sampleRate,
        durationMs = chirpDurationMs,
        startFreqHz = startFreqHz,
        endFreqHz = endFreqHz,
        tukeyAlpha = chirpTukeyAlpha
    )

    @SuppressLint("MissingPermission")
    fun measureOnce(maxRangeMeters: Double = 4.0, minRangeMeters: Double = 0.3): SonarResult {
        val recordSampleCount = (sampleRate * recordWindowMs / 1000.0).toInt()

        val recorder = buildRawAudioRecord(recordSampleCount)
            ?: return SonarResult(false, errorMessage = "AudioRecord init sikertelen", audioSourceUsed = lastAudioSourceUsed)

        val track = buildChirpAudioTrack()
        val recorded = ShortArray(recordSampleCount)
        var filled = 0
        var silenced: Boolean? = null

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return SonarResult(
                    false,
                    errorMessage = "A felvétel nem indult el – lehet, hogy egy másik felvétel foglalja a mikrofont",
                    audioSourceUsed = lastAudioSourceUsed
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
            audioSourceUsed = lastAudioSourceUsed,
            clientSilenced = silenced,
            rawPeakAmplitude = rawPeak,
            clippedSamples = clipped
        )

        Log.i(TAG, "filled=$filled rawPeak=$rawPeak clipped=$clipped silenced=$silenced")

        if (filled < recordSampleCount / 2) {
            return base.copy(errorMessage = "Túl kevés audio minta érkezett ($filled/$recordSampleCount)")
        }
        if (rawPeak == 0) {
            return base.copy(errorMessage = "Csak nullák jöttek a mikrofonról – a felvétel el van némítva (fut másik felvétel, pl. a spektrogram?)")
        }

        return processRecording(recorded, filled, maxRangeMeters, minRangeMeters, base)
    }

    // ---- Signal processing -------------------------------------------------

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

        val envelope = matchedFilterEnvelope(recorded, validLength, chirpPcm)

        var directIdx = 0
        var directVal = 0.0
        for (k in 0..lastValidLag) {
            if (envelope[k] > directVal) {
                directVal = envelope[k]
                directIdx = k
            }
        }

        val noiseLevel = median(envelope, 0, lastValidLag + 1)
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

        val minLag = directIdx + rangeToSamples(minRangeMeters)
        val wantedMaxLag = directIdx + rangeToSamples(maxRangeMeters)
        val maxLag = min(lastValidLag, wantedMaxLag)

        if (maxLag <= minLag) {
            return withDirect.copy(
                errorMessage = "A chirp túl későn érkezett az ablakban (${"%.0f".format(directTimeMs)} ms) – növeld a recordWindowMs-t"
            )
        }

        val echoThreshold = noiseLevel * 10.0.pow(ECHO_THRESHOLD_DB / 20.0)

        val candidates = mutableListOf<Int>()
        for (k in max(minLag, 1)..min(maxLag, envelope.size - 2)) {
            val v = envelope[k]
            if (v > echoThreshold && v >= envelope[k - 1] && v > envelope[k + 1]) {
                candidates.add(k)
            }
        }

        val bandwidthHz = abs(endFreqHz - startFreqHz)
        val minSeparation = max(1, (2.0 * sampleRate / bandwidthHz).toInt())
        val accepted = mutableListOf<Int>()
        for (k in candidates.sortedByDescending { envelope[it] }) {
            if (accepted.none { abs(it - k) < minSeparation }) {
                accepted.add(k)
                if (accepted.size >= MAX_ECHOES) break
            }
        }

        val echoes = accepted.map { k ->
            Echo(
                distanceMeters = samplesToRange(k - directIdx),
                levelDb = 20.0 * log10(envelope[k] / noiseLevel)
            )
        }

        Log.i(TAG, "echoes: $echoes")

        return withDirect.copy(
            success = true,
            echoes = echoes.sortedBy { it.distanceMeters },
            primaryDistanceMeters = echoes.firstOrNull()?.distanceMeters
        )
    }

    private fun samplesToRange(deltaSamples: Int): Double {
        val deltaPath = deltaSamples.toDouble() / sampleRate * SPEED_OF_SOUND_M_S
        return (deltaPath + speakerMicDistanceMeters) / 2.0
    }

    private fun rangeToSamples(rangeMeters: Double): Int {
        val deltaPath = max(0.0, 2.0 * rangeMeters - speakerMicDistanceMeters)
        return (deltaPath / SPEED_OF_SOUND_M_S * sampleRate).toInt()
    }

    private fun matchedFilterEnvelope(recorded: ShortArray, validLength: Int, template: ShortArray): DoubleArray {
        val fftSize = nextPowerOfTwo(validLength + template.size - 1)

        val a = DoubleArray(2 * fftSize)
        for (i in 0 until validLength) a[2 * i] = recorded[i].toDouble()

        val b = DoubleArray(2 * fftSize)
        for (i in template.indices) b[2 * i] = template[i].toDouble()

        val fft = DoubleFFT_1D(fftSize.toLong())
        fft.complexForward(a)
        fft.complexForward(b)

        val half = fftSize / 2
        for (i in 0 until fftSize) {
            val ar = a[2 * i]; val ai = a[2 * i + 1]
            val br = b[2 * i]; val bi = -b[2 * i + 1] // conj(B)
            val weight = when {
                i == 0 || i == half -> 1.0
                i < half -> 2.0
                else -> 0.0
            }
            a[2 * i] = (ar * br - ai * bi) * weight
            a[2 * i + 1] = (ar * bi + ai * br) * weight
        }

        fft.complexInverse(a, true)

        return DoubleArray(fftSize) { k ->
            val re = a[2 * k]
            val im = a[2 * k + 1]
            sqrt(re * re + im * im)
        }
    }

    private fun median(values: DoubleArray, from: Int, toExclusive: Int): Double {
        val slice = values.copyOfRange(from, toExclusive)
        if (slice.isEmpty()) return 0.0
        slice.sort()
        return slice[slice.size / 2]
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    // ---- Audio I/O setup -----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun buildRawAudioRecord(minSampleCount: Int): AudioRecord? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = max(minBufferBytes, minSampleCount * 2) * 2

        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.MIC to "MIC (fallback)"
        )

        for ((source, label) in sourcesToTry) {
            try {
                val recorder = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    disableAudioEffectsIfPresent(recorder.audioSessionId)
                    lastAudioSourceUsed = label
                    Log.i(TAG, "AudioRecord source in use: $label")
                    return recorder
                }
                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "source $label failed: ${e.message}")
            }
        }
        lastAudioSourceUsed = "N/A (init failed)"
        return null
    }

    private fun disableAudioEffectsIfPresent(audioSessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)?.setEnabled(false)
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(audioSessionId)?.setEnabled(false)
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)?.setEnabled(false)
            }
        } catch (_: Exception) {
        }
    }

    private fun buildChirpAudioTrack(): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack(
            attributes,
            format,
            chirpPcm.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManagerSessionId.DEFAULT
        )
        track.write(chirpPcm, 0, chirpPcm.size)
        return track
    }
}

private object AudioManagerSessionId {
    const val DEFAULT = 0
}
