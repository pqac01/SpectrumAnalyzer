package com.example.spectrumanalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SpectrumView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var magnitudes: FloatArray = floatArrayOf()
    private val paint = Paint().apply {
        color = Color.parseColor("#00FF00")
        style = Paint.Style.FILL
    }

    fun updateData(newMagnitudes: FloatArray) {
        this.magnitudes = newMagnitudes
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (magnitudes.isEmpty()) return

        val barWidth = width.toFloat() / magnitudes.size

        for (i in magnitudes.indices) {
            val barHeight = magnitudes[i] * (height / 100f)

            val left = i * barWidth
            val right = (i + 1) * barWidth
            val top = height - barHeight.coerceAtMost(height.toFloat())
            val bottom = height.toFloat()

            canvas.drawRect(left, top, right, bottom, paint)
        }
    }
}