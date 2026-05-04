package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.sqrt

class AudioAnalyzer(
    val sampleRate: Int,
    val bufferSize: Int,
    val channelConfig: Int = 1,
    val smoothingFactor: Float = 0.6f,
    private val onDataReady: (FloatArray) -> Unit
) {
    private val channelCount =
        if (channelConfig == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
    private val audioBuffer = ShortArray(bufferSize * channelCount)

    private var isRunning = false
    private var previousMagnitudes: FloatArray? = null

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
        isRunning = true

        Thread {
            val recorder = AudioRecord(
                getOptimalAudioSource(),
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
            val fftBuffer = DoubleArray(bufferSize * 2)
            val fft = DoubleFFT_1D(bufferSize.toLong())

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                isRunning = false
                return@Thread
            }

            recorder.startRecording()

            while (isRunning) {
                val readCount = recorder.read(audioBuffer, 0, bufferSize)
                if (readCount <= 0) continue

                monitor?.feedAudio(audioBuffer, readCount)

                // 1. Ablakfüggvény
                for (i in 0 until bufferSize) {
                    val window = 0.5 * (1 - Math.cos(2 * Math.PI * i / (bufferSize - 1)))
                    fftBuffer[i] = audioBuffer[i].toDouble() * window
                }

                // 2. FFT
                fft.realForward(fftBuffer)

                // 3. Magnitúdó számítás és exp. simítás
                val smoothedResult = FloatArray(bufferSize / 2)
                val prev = previousMagnitudes ?: FloatArray(bufferSize / 2)

                for (i in 0 until bufferSize / 2) {
                    val re = fftBuffer[2 * i]
                    val im = fftBuffer[2 * i + 1]
                    val mag = sqrt(re * re + im * im)

                    var db = 20 * Math.log10(mag + 1.0)
                    val noiseFloor = 20.0
                    db = if (db < noiseFloor) 0.0 else db - noiseFloor

                    val minDb: Double = 25.0
                    val maxDb: Double = 85.0
                    val normalized = ((db - minDb) / (maxDb - minDb)).coerceIn(0.0, 1.0)

                    val xt = normalized.toFloat() * 100f

                    smoothedResult[i] = (smoothingFactor * xt) + ((1f - smoothingFactor) * prev[i])
                }

                previousMagnitudes = smoothedResult

                onDataReady(smoothedResult)
            }

            recorder.stop()
            recorder.release()
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}