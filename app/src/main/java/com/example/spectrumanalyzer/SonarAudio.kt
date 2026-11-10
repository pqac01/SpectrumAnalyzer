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
import android.util.Log
import kotlin.math.max

object SonarAudio {
    private const val TAG = "SonarAudio"

    class RawRecorder(val record: AudioRecord, val sourceLabel: String)

    @SuppressLint("MissingPermission")
    fun buildRawAudioRecord(sampleRate: Int, minSampleCount: Int): RawRecorder? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = max(minBufferBytes, minSampleCount * 2) * 2 // headroom, 2 bytes/sample

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
                    Log.i(TAG, "AudioRecord source in use: $label")
                    return RawRecorder(recorder, label)
                }
                recorder.release()
            } catch (e: Exception) {
                Log.w(TAG, "source $label failed: ${e.message}")
            }
        }
        return null
    }

    fun buildStaticTrack(sampleRate: Int, pcm: ShortArray, loop: Boolean): AudioTrack {
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
            pcm.size * 2,
            AudioTrack.MODE_STATIC,
            AUDIO_SESSION_ID_DEFAULT
        )
        track.write(pcm, 0, pcm.size)
        if (loop) {
            track.setLoopPoints(0, pcm.size, -1)
        }
        return track
    }

    private const val AUDIO_SESSION_ID_DEFAULT = 0

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
}
