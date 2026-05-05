package com.example.spectrumanalyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SpectrogramView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint: Paint = Paint().apply { isAntiAlias = false }

    // A teljes spektrogram pixeleit ebben a tömbben tároljuk
    private var pixelBuffer: IntArray? = null
    private var spectrogramBitmap: Bitmap? = null

    // Ez mutatja meg, hol tartunk éppen a rajzolásban (vízszintes pozíció)
    private var writePointer = 0

    private var yToFftIndexTable = IntArray(0)
    private var currentNextSliceSize: Int? = null

    var settings: AnalyzerSettings? = null
        set(value) {
            field = value
            precomputeIndexTable()
            postInvalidate()
        }

    // Amikor a View mérete megváltozik, újrageneráljuk a buffereket
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            pixelBuffer = IntArray(w * h) { Color.BLACK }
            spectrogramBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            writePointer = 0
            precomputeIndexTable()
        }
    }

    fun updateData(newSlice: FloatArray) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || pixelBuffer == null) return

        if (currentNextSliceSize != newSlice.size) {
            currentNextSliceSize = newSlice.size
            precomputeIndexTable()
        }

        val currentPalette = settings?.palette ?: SpectrogramPalette.FIRE

        for (y in 0 until h) {
            val fftIndex = yToFftIndexTable[y]
            val value = newSlice[fftIndex]

            val intensity = (value * 2.55f).coerceIn(0f, 255f).toInt()
            val color = currentPalette.getColor(intensity)

            pixelBuffer?.set(y * w + writePointer, color)
        }

        writePointer = (writePointer + 1) % w

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        val buffer = pixelBuffer ?: return
        val bmp = spectrogramBitmap ?: return

        bmp.setPixels(buffer, 0, w, 0, 0, w, h)

        val srcRectRight = android.graphics.Rect(writePointer, 0, w, h)
        val destRectLeft = android.graphics.Rect(0, 0, w - writePointer, h)
        canvas.drawBitmap(bmp, srcRectRight, destRectLeft, paint)

        val srcRectLeft = android.graphics.Rect(0, 0, writePointer, h)
        val destRectRight = android.graphics.Rect(w - writePointer, 0, w, h)
        canvas.drawBitmap(bmp, srcRectLeft, destRectRight, paint)
    }

    fun precomputeIndexTable() {
        val s = settings
        val fftSize = currentNextSliceSize
        val h = height

        if (h <= 0 || s == null || fftSize == null) return

        yToFftIndexTable = IntArray(h) { y ->
            mapPixelToFftIndex(h - 1 - y, h, fftSize, s)
        }
    }

    private fun mapPixelToFftIndex(x: Int, totalWidth: Int, fftSize: Int, settings: AnalyzerSettings): Int {
        val nyquist = settings.sampleRate / 2.0
        val minF = 20.0
        val p = x.toDouble() / totalWidth

        val freq = when (settings.scalingMode) {
            ScalingMode.LINEAR -> p * nyquist
            ScalingMode.LOGARITHMIC -> minF * Math.pow(nyquist / minF, p)
            ScalingMode.MEL -> {
                val minMel = 2595.0 * Math.log10(1.0 + minF / 700.0)
                val maxMel = 2595.0 * Math.log10(1.0 + nyquist / 700.0)
                val currentMel = minMel + (p * (maxMel - minMel))
                700.0 * (Math.pow(10.0, currentMel / 2595.0) - 1.0)
            }
        }

        return (freq * fftSize / (settings.sampleRate / 2.0)).toInt().coerceIn(0, fftSize - 1)
    }
}