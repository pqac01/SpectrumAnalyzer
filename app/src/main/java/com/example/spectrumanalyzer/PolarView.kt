package com.example.spectrumanalyzer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max

class PolarView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    data class Point(val angleDeg: Float, val distanceMeters: Float)

    var maxRangeMeters: Float = 4f
        set(value) { field = value; postInvalidate() }

    private val points = mutableListOf<Point>()

    private val gridPaint = Paint().apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = 28f
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply {
        color = Color.rgb(255, 80, 80)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val sweepPaint = Paint().apply {
        color = Color.argb(60, 80, 220, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    fun addPoint(angleDeg: Float, distanceMeters: Float) {
        points.add(Point(angleDeg, distanceMeters))
        postInvalidate()
    }

    fun clear() {
        points.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val outerRadius = (max(1f, minOf(w, h)) / 2f) * 0.9f
        val range = max(0.1f, maxRangeMeters)

        drawGrid(canvas, cx, cy, outerRadius, range)

        for (p in points) {
            val r = (p.distanceMeters.coerceIn(0f, range) / range) * outerRadius
            val angleRad = Math.toRadians((p.angleDeg - 90.0)).toFloat()
            val px = cx + r * cos(angleRad)
            val py = cy + r * sin(angleRad)
            canvas.drawCircle(px, py, 8f, pointPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float, outerRadius: Float, range: Float) {
        val ringCount = 4
        for (ring in 1..ringCount) {
            val r = outerRadius * ring / ringCount
            canvas.drawCircle(cx, cy, r, gridPaint)
            val labelMeters = range * ring / ringCount
            canvas.drawText(String.format("%.1fm", labelMeters), cx + 6f, cy - r + 24f, labelPaint)
        }

        for (deg in 0 until 360 step 30) {
            val angleRad = Math.toRadians((deg - 90.0)).toFloat()
            val x = cx + outerRadius * cos(angleRad)
            val y = cy + outerRadius * sin(angleRad)
            canvas.drawLine(cx, cy, x, y, gridPaint)
        }

        canvas.drawText("0°", cx - 10f, cy - outerRadius - 10f, labelPaint)
    }
}
