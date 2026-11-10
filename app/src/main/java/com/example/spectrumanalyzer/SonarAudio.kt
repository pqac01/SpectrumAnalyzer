package com.example.spectrumanalyzer

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.max

object SonarAudio {

    class RawRecorder(val record: AudioRecord, val sourceLabel: String)

    @SuppressLint("MissingPermission")
    fun buildRawAudioRecord(sampleRate: Int, minSampleCount: Int): RawRecorder? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = max(minBufferBytes, minSampleCount * 2) * 2 // headroom, 2 bytes/sample

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        return RawRecorder(recorder, "UNPROCESSED")
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
}
