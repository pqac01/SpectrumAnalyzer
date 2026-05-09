package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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
    @Volatile var isRecordingToFile: Boolean = false
    private var isRunning = false

    private var previousMagnitudes: FloatArray? = null

    private val windowFactors = DoubleArray(bufferSize) { i ->
        0.5 * (1.0 - cos(2.0 * Math.PI * i / bufferSize))
    }

    private val fftBuffer = DoubleArray(bufferSize)
    private val audioBuffer = ShortArray(bufferSize)
    private val fft = DoubleFFT_1D(bufferSize.toLong())
    var monitor: AudioMonitor? = null
    var pcmWriter: PCMFileWriter? = null

    private fun getOptimalAudioSource(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaRecorder.AudioSource.VOICE_PERFORMANCE
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    fun processAudioFrame(audioData: ShortArray, readCount: Int, isTesting: Boolean = true) {
        if (readCount <= 0) return

        // 1. Windowing
        for (i in 0 until bufferSize) {
            if (i < readCount) {
                val sample = audioData[i].toDouble()
                fftBuffer[i] = sample * windowFactors[i]
            } else {
                fftBuffer[i] = 0.0
            }
        }

        // 2. FFT
        fft.realForward(fftBuffer)

        // 3. Calculate magnitudes and dB
        val resultSize = bufferSize / 2
        val currentMagnitudes = FloatArray(resultSize)
        val prev = previousMagnitudes ?: FloatArray(resultSize)

        for (i in 0 until resultSize) {
            val re: Double
            val im: Double

            if (i == 0) {
                re = fftBuffer[0]
                im = 0.0
            } else if (i == resultSize - 1) {
                re = fftBuffer[1]
                im = 0.0
            } else {
                re = fftBuffer[2 * i]
                im = fftBuffer[2 * i + 1]
            }

            val mag = sqrt(re * re + im * im)
            val db = 20 * Math.log10(mag + 1.0)

            if (isTesting) {
                currentMagnitudes[i] = db.toFloat()
            }
            else {
                val normalized = ((db.toFloat() - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
                val value = normalized * 100f

                currentMagnitudes[i] = (smoothingFactor * value) + ((1f - smoothingFactor) * prev[i])
            }
        }

        previousMagnitudes = currentMagnitudes
        onDataReady(currentMagnitudes)
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

                if (readCount > 0) {
                    if (isRecordingToFile) {
                        pcmWriter?.appendData(audioBuffer, readCount)
                    }
                    monitor?.feedAudio(audioBuffer, readCount)

                    processAudioFrame(audioBuffer, readCount)
                }
            }

            try {
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
            }
        }.start()
    }

    fun stop() {
        isRunning = false
    }
}