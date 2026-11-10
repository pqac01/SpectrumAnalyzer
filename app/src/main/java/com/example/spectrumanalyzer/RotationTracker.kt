package com.example.spectrumanalyzer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
class RotationTracker(context: Context) : SensorEventListener {

    companion object {
        private const val HISTORY = 512                 // ~10 s at SENSOR_DELAY_GAME (~50 Hz)
        private const val MAX_EXTRAPOLATION_NS = 100_000_000L
        private const val SPEED_SMOOTHING = 0.2

        private fun wrap360(deg: Double): Float = (((deg % 360.0) + 360.0) % 360.0).toFloat()
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = sensor != null

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val times = LongArray(HISTORY)
    private val headings = DoubleArray(HISTORY) // unwrapped, relative to the start
    private var count = 0
    private var head = 0 // next write index

    private var lastRawDeg: Double? = null
    private var unwrappedDeg = 0.0
    private var referenceDeg: Double? = null

    @Volatile var angularSpeedDegPerSec: Double = 0.0
        private set

    @Volatile var currentHeadingDeg: Float = 0f
        private set

    @Volatile var totalTurnedDeg: Double = 0.0
        private set

    fun start() {
        reset()
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    @Synchronized
    fun reset() {
        count = 0
        head = 0
        lastRawDeg = null
        unwrappedDeg = 0.0
        referenceDeg = null
        angularSpeedDegPerSec = 0.0
        currentHeadingDeg = 0f
        totalTurnedDeg = 0.0
    }

    @Synchronized
    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val rawDeg = Math.toDegrees(orientation[0].toDouble()) // azimuth, -180..180, clockwise

        val last = lastRawDeg
        if (last == null) {
            unwrappedDeg = rawDeg
        } else {
            var delta = rawDeg - last
            if (delta > 180.0) delta -= 360.0 else if (delta < -180.0) delta += 360.0
            unwrappedDeg += delta
        }
        lastRawDeg = rawDeg

        val reference = referenceDeg ?: unwrappedDeg.also { referenceDeg = it }
        val heading = unwrappedDeg - reference

        if (count > 0) {
            val prev = (head - 1 + HISTORY) % HISTORY
            val dt = (event.timestamp - times[prev]) / 1e9
            if (dt > 0.0) {
                val instant = abs(heading - headings[prev]) / dt
                angularSpeedDegPerSec =
                    (1.0 - SPEED_SMOOTHING) * angularSpeedDegPerSec + SPEED_SMOOTHING * instant
            }
        }

        times[head] = event.timestamp
        headings[head] = heading
        head = (head + 1) % HISTORY
        if (count < HISTORY) count++

        currentHeadingDeg = wrap360(heading)
        totalTurnedDeg = abs(heading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Synchronized
    fun headingAt(timestampNs: Long): Float? {
        if (count < 2) return null
        val oldest = (head - count + HISTORY) % HISTORY
        if (timestampNs < times[oldest]) return null

        var prevIdx = oldest
        for (n in 1 until count) {
            val idx = (oldest + n) % HISTORY
            if (times[idx] >= timestampNs) {
                val t0 = times[prevIdx]
                val t1 = times[idx]
                val f = if (t1 > t0) (timestampNs - t0).toDouble() / (t1 - t0) else 0.0
                return wrap360(headings[prevIdx] + f * (headings[idx] - headings[prevIdx]))
            }
            prevIdx = idx
        }

        val newest = (head - 1 + HISTORY) % HISTORY
        return if (timestampNs - times[newest] < MAX_EXTRAPOLATION_NS) wrap360(headings[newest]) else null
    }
}
