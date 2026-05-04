package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.sqrt

class AudioAnalyzer(private val onDataReady: (FloatArray) -> Unit) {

    private val sampleRate = 44100
    private val bufferSize = 2048 // Fontos: 2 hatványa legyen az FFT miatt!
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun start() {
        isRunning = true

        Thread {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            val audioBuffer = ShortArray(bufferSize)
            val fftBuffer = DoubleArray(bufferSize * 2)
            val fft = DoubleFFT_1D(bufferSize.toLong())
            val magnitudes = FloatArray(bufferSize / 2)

            recorder.startRecording()

            while (isRunning) {
                val readCount = recorder.read(audioBuffer, 0, bufferSize)
                if (readCount <= 0) continue

                // 1. Ablakfüggvény és Double konverzió
                for (i in 0 until bufferSize) {
                    val window = 0.5 * (1 - Math.cos(2 * Math.PI * i / (bufferSize - 1)))
                    fftBuffer[i] = audioBuffer[i].toDouble() * window
                }

                // 2. FFT (csak realForward, ez gyorsabb és egyszerűbb)
                fft.realForward(fftBuffer)

                // 3. Magnitúdó számítás Decibel-szerű skálán
                for (i in 0 until bufferSize / 2) {
                    val re = fftBuffer[2 * i]
                    val im = fftBuffer[2 * i + 1]
                    val mag = sqrt(re * re + im * im)

                    // Logaritmikus skálázás (dB), hogy ne legyen minden zajos
                    // A 10.0 és 1.0 értékekkel finomhangolhatod az érzékenységet
                    var db = 20 * Math.log10(mag + 1.0)

                    // Zaj filter
                    val noiseFloor = 40.0
                    db = if (db < noiseFloor) 0.0 else db - noiseFloor

                    // Normalizáljuk egy kezelhető tartományba (pl. 0-100)
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