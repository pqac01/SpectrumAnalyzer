package com.example.spectrumanalyzer

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.SystemClock
import android.util.Log
import kotlin.math.log10

class SonarStreamer(
    private val sampleRate: Int = 48000,
    chirpDurationMs: Double = 20.0,
    periodMs: Double = 50.0,
    maxRangeMeters: Double = 4.0,
    private val onProfile: (RangeProfile) -> Unit,
    private val onStatus: (Status) -> Unit
) {
    data class Status(
        val locked: Boolean,
        val directSnrDb: Double?,
        val audioSource: String,
        val message: String? = null
    )

    companion object {
        private const val TAG = "SonarStreamer"
        private const val MIN_DIRECT_SNR_DB = 15.0
        private const val ACQUIRE_WINDOW_MS = 700.0
        private const val LOST_PINGS_BEFORE_REACQUIRE = 5
    }

    private val chirp = ChirpGenerator.generate(
        sampleRate = sampleRate,
        durationMs = chirpDurationMs,
        startFreqHz = 18000.0,
        endFreqHz = 22000.0,
        tukeyAlpha = 1.0
    )
    private val filter = MatchedFilter(chirp)
    private val periodSamples = (sampleRate * periodMs / 1000.0).toInt()
    private val maxRangeSamples = SonarGeometry.rangeToSamples(maxRangeMeters, sampleRate)
    private val searchMargin = sampleRate / 1000 // +-1 ms
    private val segmentLength = 2 * searchMargin + maxRangeSamples + chirp.size

    init {
        require(periodSamples > chirp.size + maxRangeSamples + 2 * searchMargin) {
            "periodMs too short for this chirp and max range"
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread({ runLoop() }, "SonarStreamer").apply { start() }
    }

    fun stop() {
        running = false
        try { worker?.join(1000) } catch (_: InterruptedException) {}
        worker = null
    }

    private class SampleBuffer(capacity: Int) {
        val data = ShortArray(capacity)
        var startAbs = 0L
        var length = 0

        fun append(src: ShortArray, n: Int): Boolean {
            var dropped = false
            if (length + n > data.size) {
                discardBefore(startAbs + (length + n - data.size))
                dropped = true
            }
            System.arraycopy(src, 0, data, length, n)
            length += n
            return dropped
        }

        fun discardBefore(abs: Long) {
            val k = (abs - startAbs).coerceIn(0L, length.toLong()).toInt()
            if (k == 0) return
            System.arraycopy(data, k, data, 0, length - k)
            length -= k
            startAbs += k
        }
    }

    private fun runLoop() {
        val raw = SonarAudio.buildRawAudioRecord(sampleRate, sampleRate) // ~1 s of headroom
        if (raw == null) {
            onStatus(Status(false, null, "N/A", "AudioRecord init sikertelen"))
            running = false
            return
        }
        val recorder = raw.record
        val periodPcm = ShortArray(periodSamples).also { chirp.copyInto(it) }
        val track = SonarAudio.buildStaticTrack(sampleRate, periodPcm, loop = true)

        val buffer = SampleBuffer(sampleRate * 2)
        val chunk = ShortArray(periodSamples / 2)
        var totalRead = 0L

        val timestamp = AudioTimestamp()
        var anchorFrame = 0L
        var anchorNs = SystemClock.elapsedRealtimeNanos()

        val acquireSamples = (sampleRate * ACQUIRE_WINDOW_MS / 1000.0).toInt()
        var nextDirectAbs = -1L // -1 = acquiring
        var lost = 0

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onStatus(Status(false, null, raw.sourceLabel, "A felvétel nem indult el – foglalt a mikrofon?"))
                return
            }
            track.play()
            onStatus(Status(false, null, raw.sourceLabel, "chirp keresése…"))

            while (running) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n < 0) {
                    onStatus(Status(false, null, raw.sourceLabel, "Felvételi hiba ($n)"))
                    break
                }
                if (n == 0) continue

                if (buffer.append(chunk, n)) nextDirectAbs = -1L // fell behind: re-acquire
                totalRead += n
                if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    anchorFrame = timestamp.framePosition
                    anchorNs = timestamp.nanoTime
                } else {
                    anchorFrame = totalRead
                    anchorNs = SystemClock.elapsedRealtimeNanos()
                }

                var progressed = true
                while (progressed && running) {
                    progressed = false

                    if (nextDirectAbs < 0) {
                        if (buffer.length >= acquireSamples) {
                            val found = acquire(buffer.data, acquireSamples)
                            if (found != null) {
                                nextDirectAbs = buffer.startAbs + found + periodSamples
                                lost = 0
                                buffer.discardBefore(nextDirectAbs - searchMargin)
                            } else {
                                onStatus(Status(false, null, raw.sourceLabel, "Nem található a chirp a felvételben"))
                                buffer.discardBefore(buffer.startAbs + acquireSamples - periodSamples)
                            }
                            progressed = true
                        }
                        continue
                    }

                    val segmentStartAbs = nextDirectAbs - searchMargin
                    val offset = (segmentStartAbs - buffer.startAbs).toInt()
                    if (offset < 0) {
                        nextDirectAbs = -1L
                        progressed = true
                        continue
                    }
                    if (offset + segmentLength > buffer.length) continue // wait for more audio

                    val ping = trackOne(buffer.data, offset)
                    if (ping != null) {
                        val directAbs = segmentStartAbs + ping.directOffset
                        val timeNs = anchorNs + (directAbs - anchorFrame) * 1_000_000_000L / sampleRate
                        onProfile(ping.profile.copy(timestampNs = timeNs))
                        onStatus(Status(true, ping.snrDb, raw.sourceLabel))
                        nextDirectAbs = directAbs + periodSamples
                        lost = 0
                    } else {
                        lost++
                        nextDirectAbs += periodSamples
                        if (lost >= LOST_PINGS_BEFORE_REACQUIRE) {
                            nextDirectAbs = -1L
                            onStatus(Status(false, null, raw.sourceLabel, "Elveszett a jel, újrakeresés…"))
                        }
                    }

                    if (nextDirectAbs >= 0) buffer.discardBefore(nextDirectAbs - searchMargin)
                    progressed = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "streaming failed", e)
            onStatus(Status(false, null, raw.sourceLabel, "Hiba: ${e.message}"))
        } finally {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            running = false
        }
    }
    private fun acquire(data: ShortArray, length: Int): Int? {
        val lastValidLag = length - chirp.size
        val envelope = filter.envelope(data, 0, length)
        var best = 0
        for (k in 0..lastValidLag) if (envelope[k] > envelope[best]) best = k
        val noise = MatchedFilter.median(envelope, 0, lastValidLag + 1)
        if (noise <= 0.0) return null
        val snrDb = 20.0 * log10(envelope[best] / noise)
        Log.i(TAG, "acquire: peak @ $best, SNR ${"%.1f".format(snrDb)} dB")
        return if (snrDb >= MIN_DIRECT_SNR_DB) best else null
    }

    private class Ping(val directOffset: Int, val profile: RangeProfile, val snrDb: Double)
    private fun trackOne(data: ShortArray, offset: Int): Ping? {
        val envelope = filter.envelope(data, offset, segmentLength)
        val lastValidLag = segmentLength - chirp.size

        var direct = 0
        for (k in 0..2 * searchMargin) if (envelope[k] > envelope[direct]) direct = k
        val directValue = envelope[direct]

        val noise = MatchedFilter.median(envelope, 0, lastValidLag + 1)
        if (noise <= 0.0 || directValue <= 0.0) return null
        val snrDb = 20.0 * log10(directValue / noise)
        if (snrDb < MIN_DIRECT_SNR_DB) return null
        val values = FloatArray(maxRangeSamples + 1) { i ->
            (envelope[direct + i] / directValue).toFloat()
        }
        val profile = RangeProfile(
            angleDeg = 0f,
            values = values,
            noiseLevel = (noise / directValue).toFloat()
        )
        return Ping(direct, profile, snrDb)
    }
}
