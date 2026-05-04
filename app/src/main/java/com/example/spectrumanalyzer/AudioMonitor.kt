package com.example.spectrumanalyzer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioMonitor(private val sampleRate: Int, private val bufferSize: Int) {

    private var audioTrack: AudioTrack? = null

    fun start() {
        if (audioTrack != null) stop()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    fun feedAudio(audioData: ShortArray, size: Int) {
        try {
            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.write(audioData, 0, size)
            }
        } catch (e: Exception) {

        }
    }

    fun stop() {
        try {
            audioTrack?.let {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                    it.release()
                }
            }
        } catch (e: Exception) {

        } finally {
            audioTrack = null
        }
    }
}