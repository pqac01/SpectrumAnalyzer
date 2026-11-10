package com.example.spectrumanalyzer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
class PolarView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    data class Marker(val angleDeg: Float, val distanceMeters: Float)

    var maxRangeMeters: Float = 4f
        set(value) { field = value; postInvalidate() }

    var palette: SpectrogramPalette = SpectrogramPalette.FIRE
        set(value) { field = value; rebuildBitmap(); postInvalidate() }
    var fillGapDeg: Float = 10f
        set(value) { field = value; rebuildBitmap(); postInvalidate() }
    var sweepAngleDeg: Float? = null
        set(value) { field = value; postInvalidate() }

    private var image: PpiImage? = null
    private val markers = mutableListOf<Marker>()

    private val imageSize = 400
    private val pixelBuffer = IntArray(imageSize * imageSize)
    private val imageBitmap: Bitmap = Bitmap.createBitmap(imageSize, imageSize, Bitmap.Config.ARGB_8888)

    private var pixelBin = IntArray(0)
    private var pixelCell = IntArray(0)
    private var tableBinCount = -1
    private var tableRangeCells = -1

    private val bitmapPaint = Paint().apply { isFilterBitmap = true }

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

    private val markerPaint = Paint().apply {
        color = Color.rgb(80, 220, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val sweepPaint = Paint().apply {
        color = Color.argb(160, 80, 220, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    fun setImage(newImage: PpiImage) {
        image = newImage
        rebuildBitmap()
        postInvalidate()
    }

    fun setMarkers(newMarkers: List<Marker>) {
        markers.clear()
        markers.addAll(newMarkers)
        postInvalidate()
    }

    fun clear() {
        image = null
        markers.clear()
        sweepAngleDeg = null
        rebuildBitmap()
        postInvalidate()
    }

    private fun rebuildTables(binCount: Int, rangeCells: Int) {
        val total = imageSize * imageSize
        pixelBin = IntArray(total)
        pixelCell = IntArray(total)
        val center = imageSize / 2f
        val radius = imageSize / 2f
        val binDeg = 360f / binCount

        for (y in 0 until imageSize) {
            for (x in 0 until imageSize) {
                val idx = y * imageSize + x
                val dx = x + 0.5f - center
                val dy = y + 0.5f - center
                val r = sqrt(dx * dx + dy * dy)
                if (r >= radius) {
                    pixelCell[idx] = -1
                    continue
                }
                var deg = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
                if (deg < 0f) deg += 360f
                pixelBin[idx] = min(binCount - 1, (deg / binDeg).toInt())
                pixelCell[idx] = min(rangeCells - 1, (r / radius * rangeCells).toInt())
            }
        }
        tableBinCount = binCount
        tableRangeCells = rangeCells
    }

    private fun rebuildBitmap() {
        val img = image
        if (img == null) {
            pixelBuffer.fill(Color.TRANSPARENT)
            imageBitmap.setPixels(pixelBuffer, 0, imageSize, 0, 0, imageSize, imageSize)
            return
        }

        val binCount = img.bins.size
        if (binCount != tableBinCount || img.rangeCells != tableRangeCells) {
            rebuildTables(binCount, img.rangeCells)
        }

        val gapBins = (fillGapDeg / img.angleBinDeg).toInt()
        val source = IntArray(binCount) { -1 }
        for (b in 0 until binCount) {
            if (img.bins[b] != null) {
                source[b] = b
                continue
            }
            for (d in 1..gapBins) {
                val left = (b - d + binCount) % binCount
                val right = (b + d) % binCount
                if (img.bins[left] != null) { source[b] = left; break }
                if (img.bins[right] != null) { source[b] = right; break }
            }
        }

        val colors = IntArray(256) { palette.getColor(it) }
        for (i in pixelBuffer.indices) {
            val cell = pixelCell[i]
            if (cell < 0) {
                pixelBuffer[i] = Color.TRANSPARENT
                continue
            }
            val src = source[pixelBin[i]]
            pixelBuffer[i] = if (src < 0) Color.BLACK else colors[img.bins[src]!![cell].toInt() and 0xFF]
        }
        imageBitmap.setPixels(pixelBuffer, 0, imageSize, 0, 0, imageSize, imageSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val outerRadius = (max(1f, min(w, h)) / 2f) * 0.9f
        val range = max(0.1f, maxRangeMeters)

        canvas.drawBitmap(
            imageBitmap,
            null,
            RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius),
            bitmapPaint
        )

        drawGrid(canvas, cx, cy, outerRadius, range)

        for (m in markers) {
            val r = (m.distanceMeters.coerceIn(0f, range) / range) * outerRadius
            val angleRad = Math.toRadians((m.angleDeg - 90.0)).toFloat() // -90: 0 deg = up
            canvas.drawCircle(cx + r * cos(angleRad), cy + r * sin(angleRad), 10f, markerPaint)
        }

        sweepAngleDeg?.let { deg ->
            val angleRad = Math.toRadians((deg - 90.0)).toFloat()
            canvas.drawLine(cx, cy, cx + outerRadius * cos(angleRad), cy + outerRadius * sin(angleRad), sweepPaint)
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
            canvas.drawLine(cx, cy, cx + outerRadius * cos(angleRad), cy + outerRadius * sin(angleRad), gridPaint)
        }

        canvas.drawText("0°", cx - 10f, cy - outerRadius - 10f, labelPaint)
    }
}
