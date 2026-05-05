package com.example.spectrumanalyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SpectrogramView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint: Paint = Paint()
    private val slicePaint = Paint()
    private var bitmap: Bitmap? = null
    private var canvasForBitmap: Canvas? = null
    private var yToFftIndexTable = IntArray(0)
    private var currentNextSliceSize: Int? = null
    private var currentScalingMode: ScalingMode? = null
    var settings: AnalyzerSettings? = null
        set(value) {
            field = value
            currentScalingMode = value?.scalingMode
            precomputeIndexTable()
            postInvalidate()
        }

    fun updateData(newSlice: FloatArray) {
        if (width <= 0 || height <= 0) return

        if (currentNextSliceSize != newSlice.size) {
            currentNextSliceSize = newSlice.size
            precomputeIndexTable()
        }

        if (yToFftIndexTable.isEmpty()) {
            precomputeIndexTable()
        }

        // Create Bitmap if not exists
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvasForBitmap = Canvas(bitmap!!)
        }

        // Shift bitmap
        canvasForBitmap?.drawBitmap(bitmap!!, -1f, 0f, null) // TODO top -1 a felfeléhez

        // Drawing
        for (y in 0 until height) {
            val fftIndex = yToFftIndexTable[y]

            val value = newSlice[fftIndex]

            val intensity = (value * 2.55f).coerceIn(0f, 255f).toInt()
            slicePaint.color = Color.rgb(intensity, intensity / 4, 255 - intensity)

            canvasForBitmap?.drawPoint((width - 1).toFloat(), y.toFloat(), slicePaint)
        }

        postInvalidate()
    }
    fun precomputeIndexTable() {
        val s = settings
        val fftSize = currentNextSliceSize

        if (height <= 0 || s == null || fftSize == null) return

        yToFftIndexTable = IntArray(height) { y ->
            mapPixelToFftIndex(height - 1 - y, height, fftSize, s)
        }
    }

    private fun mapPixelToFftIndex(x: Int, totalWidth: Int, fftSize: Int, settings: AnalyzerSettings): Int {
        val nyquist = settings.sampleRate / 2.0
        val minF = 20.0

        val p = x.toDouble() / totalWidth

        val freq = when (settings.scalingMode) {
            ScalingMode.LINEAR -> {
                p * nyquist
            }
            ScalingMode.LOGARITHMIC -> {
                minF * Math.pow(nyquist / minF, p)
            }
            ScalingMode.MEL -> {
                val minMel = 2595.0 * Math.log10(1.0 + minF / 700.0)
                val maxMel = 2595.0 * Math.log10(1.0 + nyquist / 700.0)
                val currentMel = minMel + (p * (maxMel - minMel))
                700.0 * (Math.pow(10.0, currentMel / 2595.0) - 1.0)
            }
        }

        val index = (freq * fftSize / (settings.sampleRate / 2.0)).toInt()
        return index.coerceIn(0, fftSize - 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, paint)
        }
    }
}