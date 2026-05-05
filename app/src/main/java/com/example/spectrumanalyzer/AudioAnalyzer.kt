package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.cos
import kotlin.math.sqrt

class AudioAnalyzer(
    val sampleRate: Int,
    val bufferSize: Int,
    private val onDataReady: (FloatArray) -> Unit
) {
    @Volatile var smoothingFactor: Float = 0.6f
    @Volatile var minDb: Float = 25f
    @Volatile var maxDb: Float = 85f

    private var isRunning = false
    private var previousMagnitudes: FloatArray? = null

    // Hamming window
    private val windowFactors = DoubleArray(bufferSize) { i ->
        0.5 * (1.0 - cos(2.0 * Math.PI * i / bufferSize))
    }
    private val fftBuffer = DoubleArray(bufferSize * 2)
    private val audioBuffer = ShortArray(bufferSize)
    private val fft = DoubleFFT_1D(bufferSize.toLong())
    var monitor: AudioMonitor? = null

    private fun getOptimalAudioSource(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaRecorder.AudioSource.VOICE_PERFORMANCE
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true

        Thread {
            val recorder = AudioRecord(
                getOptimalAudioSource(),
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                isRunning = false
                return@Thread
            }

            recorder.startRecording()

            while (isRunning) {
                val readCount = recorder.read(audioBuffer, 0, bufferSize)
                if (readCount <= 0) continue

                monitor?.feedAudio(audioBuffer, readCount)

                for (i in 0 until bufferSize) {
                    fftBuffer[i] = audioBuffer[i].toDouble() * windowFactors[i]
                }

                // FFT
                fft.realForward(fftBuffer)

                // Magnitude calculation
                val resultSize = bufferSize / 2
                val currentMagnitudes = FloatArray(resultSize)
                val prev = previousMagnitudes ?: FloatArray(resultSize)

                for (i in 0 until resultSize) {
                    val re = fftBuffer[2 * i]
                    val im = fftBuffer[2 * i + 1]
                    val mag = sqrt(re * re + im * im)

                    // To dB
                    var db = 20 * Math.log10(mag + 1.0)

                    // Normalize
                    val normalized = ((db.toFloat() - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
                    val value = normalized * 100f

                    // Smoothing
                    currentMagnitudes[i] = (smoothingFactor * value) + ((1f - smoothingFactor) * prev[i])
                }

                previousMagnitudes = currentMagnitudes
                onDataReady(currentMagnitudes)
            }

            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                // Már leállított recorder esetén ne crasheljen
            }
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}