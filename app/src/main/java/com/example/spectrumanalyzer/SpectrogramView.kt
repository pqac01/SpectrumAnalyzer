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
    private var bitmap: Bitmap? = null
    private var canvasForBitmap: Canvas? = null

    fun updateData(newSlice: FloatArray) {
        if (width <= 0 || height <= 0) return

        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvasForBitmap = Canvas(bitmap!!)
        }

        canvasForBitmap?.drawBitmap(bitmap!!, 0f, -1f, null)

        val slicePaint = Paint()
        val step = width.toFloat() / newSlice.size

        for (i in newSlice.indices) {
            val intensity = (newSlice[i] * 2.5f).coerceIn(0f, 255f).toInt()
            slicePaint.color = Color.rgb(intensity, intensity / 4, 255 - intensity)

            canvasForBitmap?.drawRect(
                i * step, (height - 1).toFloat(),
                (i + 1) * step, height.toFloat(),
                slicePaint
            )
        }

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, paint)
        }
    }
}