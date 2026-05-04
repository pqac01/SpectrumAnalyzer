package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.sqrt

class AudioAnalyzer(val sampleRate: Int, val bufferSize: Int, private val onDataReady: (FloatArray) -> Unit) {
    private var isRunning = false

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
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            val audioBuffer = ShortArray(bufferSize)
            val fftBuffer = DoubleArray(bufferSize * 2)
            val fft = DoubleFFT_1D(bufferSize.toLong())
            val magnitudes = FloatArray(bufferSize / 2)

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                isRunning = false
                return@Thread
            }

            recorder.startRecording()

            while (isRunning) {
                val readCount = recorder.read(audioBuffer, 0, bufferSize)
                if (readCount <= 0) continue

                monitor?.feedAudio(audioBuffer, readCount)

                // 1. Ablakfüggvény és Double konverzió
                for (i in 0 until bufferSize) {
                    val window = 0.5 * (1 - Math.cos(2 * Math.PI * i / (bufferSize - 1)))
                    fftBuffer[i] = audioBuffer[i].toDouble() * window
                }

                // 2. FFT (realForward)
                fft.realForward(fftBuffer)

                // 3. Magnitúdó számítás Decibel-szerű skálán
                for (i in 0 until bufferSize / 2) {
                    val re = fftBuffer[2 * i]
                    val im = fftBuffer[2 * i + 1]
                    val mag = sqrt(re * re + im * im)

                    var db = 20 * Math.log10(mag + 1.0)

                    // Zaj filter
                    val noiseFloor = 40.0
                    db = if (db < noiseFloor) 0.0 else db - noiseFloor

                    magnitudes[i] = db.toFloat()
                }

                onDataReady(magnitudes)
            }

            recorder.stop()
            recorder.release()
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}