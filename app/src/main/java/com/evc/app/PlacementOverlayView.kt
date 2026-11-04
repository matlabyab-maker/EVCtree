package com.evc.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Transparent player overlay: live text can be dragged and pinch-scaled; PiP video keeps its frame. */
class PlacementOverlayView(
    context: Context,
    private val onPositionChanged: (xPercent: Int, yPercent: Int) -> Unit,
    private val onTextSizeChanged: (size: Int) -> Unit = {},
    private val onVideoBoundsChanged: (xPercent: Int, yPercent: Int, widthPercent: Int) -> Unit = { _, _, _ -> },
    private val onVideoPlacementReleased: () -> Unit = {}
) : View(context) {
    enum class Mode { NONE, TEXT, VIDEO }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; isFakeBoldText = true }
    private var xPercent = 5
    private var yPercent = 5
    private var widthPercent = 25
    private var heightPercent = 25
    private var downX = 0f
    private var downY = 0f
    private var startXPercent = 0
    private var startYPercent = 0
    private var dragging = false
    private var resizingCorner = -1 // 0=TL, 1=TR, 2=BL, 3=BR
    private var startWidthPercent = 25
    private var startHeightPercent = 25
    private var startLeftPercent = 0
    private var startTopPercent = 0
    private var pinchStartDistance = 0f
    private var pinchStartTextSize = 36
    private var textSizeValue = 36
    private var previewText: String = ""
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 18f
        style = Paint.Style.FILL
        setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
    }
    private val previewOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    // Large, high-contrast handles make the overlay easier to grab on a touch screen.
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF087F5B.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    fun setSelection(x: Int, y: Int, width: Int, height: Int) {
        widthPercent = width.coerceIn(5, 95)
        heightPercent = height.coerceIn(5, 95)
        xPercent = x.coerceIn(0, if ((tag as? Mode) == Mode.TEXT) 98 else 100 - widthPercent)
        yPercent = y.coerceIn(0, if ((tag as? Mode) == Mode.TEXT) 98 else 100 - heightPercent)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val mode = tag as? Mode ?: Mode.NONE
        val left = width * xPercent / 100f
        val top = height * yPercent / 100f
        val right = min(width.toFloat(), left + width * widthPercent / 100f)
        val bottom = min(height.toFloat(), top + height * heightPercent / 100f)
        val color = when (mode) {
            Mode.TEXT -> 0xFFD7A6FF.toInt()
            Mode.VIDEO -> 0xFF43D9A3.toInt()
            Mode.NONE -> 0xFF70AEFF.toInt()
        }
        if (mode == Mode.TEXT && previewText.isNotBlank()) {
            // Text itself is the handle: no placement rectangle or coordinate clutter.
            val textSizePx = (textSizeValue * (height / 360f)).coerceIn(8f, 90f)
            previewTextPaint.textSize = textSizePx
            previewOutlinePaint.textSize = textSizePx
            val availableWidth = (width - left - 4f).coerceAtLeast(20f)
            val lines = mutableListOf<String>()
            var line = ""
            for (word in previewText.split(Regex("\\s+"))) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (previewTextPaint.measureText(candidate) <= availableWidth || line.isEmpty()) line = candidate
                else { lines.add(line); line = word }
            }
            if (line.isNotEmpty()) lines.add(line)
            var baseline = top + textSizePx
            val lineHeight = textSizePx * 1.18f
            for (textLine in lines) {
                if (baseline > height + textSizePx) break
                canvas.drawText(textLine, left, baseline, previewOutlinePaint)
                canvas.drawText(textLine, left, baseline, previewTextPaint)
                baseline += lineHeight
            }
            return
        }
        borderPaint.color = color
        fillPaint.color = (color and 0x00FFFFFF) or 0x26000000
        labelPaint.color = color
        canvas.drawRoundRect(RectF(left, top, right, bottom), 7f, 7f, fillPaint)
        val label = when (mode) {
            Mode.TEXT -> "متن • بکش"
            Mode.VIDEO -> "ویدیوی رویی • بکش"
            Mode.NONE -> ""
        }
        if (label.isNotEmpty()) canvas.drawText(label, left + 5f, min(bottom - 5f, top + 17f), labelPaint)
        canvas.drawRoundRect(RectF(left, top, right, bottom), 7f, 7f, borderPaint)
        if (mode == Mode.VIDEO) {
            val density = resources.displayMetrics.density
            val radius = 9f * density
            // Four visible corner grips; their touch targets are intentionally larger than their drawing.
            val corners = arrayOf(
                left to top, right to top, left to bottom, right to bottom
            )
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, radius, handleFillPaint)
                canvas.drawCircle(cx, cy, radius, handleStrokePaint)
            }
            // A center grip communicates that the whole box can be dragged to move it.
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val gripHalf = 7f * density
            canvas.drawLine(cx - gripHalf, cy, cx + gripHalf, cy, gripPaint)
            canvas.drawLine(cx, cy - gripHalf, cx, cy + gripHalf, gripPaint)
            canvas.drawCircle(cx, cy, 3f * density, handleStrokePaint)
        }
    }

    fun setMode(mode: Mode) { tag = mode; invalidate() }

    fun setTextPreview(text: String, textSize: Int = 36) {
        previewText = text
        textSizeValue = textSize.coerceIn(8, 160)
        invalidate()
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || width <= 0 || height <= 0) return false
        val mode = tag as? Mode ?: Mode.NONE
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                startXPercent = xPercent; startYPercent = yPercent
                startWidthPercent = widthPercent; startHeightPercent = heightPercent
                startLeftPercent = xPercent; startTopPercent = yPercent
                val left = width * xPercent / 100f
                val top = height * yPercent / 100f
                val right = min(width.toFloat(), left + width * widthPercent / 100f)
                val bottom = min(height.toFloat(), top + height * heightPercent / 100f)
                // Generous touch target: users do not need to hit the tiny visible circle exactly.
                val handle = 46f * resources.displayMetrics.density
                resizingCorner = if (mode == Mode.VIDEO) when {
                    distance(event.x, event.y, left, top) <= handle -> 0
                    distance(event.x, event.y, right, top) <= handle -> 1
                    distance(event.x, event.y, left, bottom) <= handle -> 2
                    distance(event.x, event.y, right, bottom) <= handle -> 3
                    else -> -1
                } else -1
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.TEXT && event.pointerCount >= 2) {
                    pinchStartDistance = distance(event).coerceAtLeast(1f)
                    pinchStartTextSize = textSizeValue
                    dragging = false
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.VIDEO && resizingCorner >= 0 && event.pointerCount == 1) {
                    val dxPct = (event.x - downX) * 100f / width
                    val dyPct = (event.y - downY) * 100f / height
                    // Width is the saved size control; derive vertical drag from the displayed aspect ratio
                    // so every corner resizes the actual preview consistently instead of distorting the frame.
                    val heightPerWidth = (startHeightPercent.toFloat() / startWidthPercent.coerceAtLeast(1)).coerceAtLeast(0.05f)
                    val widthFromY = dyPct / heightPerWidth
                    val deltaFromX = if (resizingCorner == 0 || resizingCorner == 2) -dxPct else dxPct
                    val deltaFromY = if (resizingCorner == 0 || resizingCorner == 1) -widthFromY else widthFromY
                    val deltaWidth = if (kotlin.math.abs(deltaFromX) >= kotlin.math.abs(deltaFromY)) deltaFromX else deltaFromY
                    val newW = (startWidthPercent + deltaWidth.toInt()).coerceIn(5, 90)
                    val widthDelta = newW - startWidthPercent
                    val heightDelta = (widthDelta * heightPerWidth).toInt()
                    val newX = when (resizingCorner) {
                        0, 2 -> startLeftPercent - widthDelta
                        else -> startLeftPercent
                    }.coerceIn(0, 100 - newW)
                    val newY = when (resizingCorner) {
                        0, 1 -> startTopPercent - heightDelta
                        else -> startTopPercent
                    }.coerceIn(0, 95)
                    xPercent = newX; yPercent = newY
                    widthPercent = newW
                    heightPercent = (newW * heightPerWidth).toInt().coerceIn(5, 95)
                    invalidate()
                    onVideoBoundsChanged(xPercent, yPercent, widthPercent)
                    return true
                }
                if (mode == Mode.TEXT && event.pointerCount >= 2 && pinchStartDistance > 0f) {
                    val ratio = distance(event) / pinchStartDistance
                    val updated = (pinchStartTextSize * ratio).toInt().coerceIn(8, 160)
                    if (updated != textSizeValue) {
                        textSizeValue = updated
                        invalidate()
                        onTextSizeChanged(updated)
                    }
                    return true
                }
                if (!dragging || event.pointerCount > 1) return true
                val dx = ((event.x - downX) * 100f / width).toInt()
                val dy = ((event.y - downY) * 100f / height).toInt()
                val maxX = if (mode == Mode.TEXT) 98 else 100 - widthPercent
                val maxY = if (mode == Mode.TEXT) 98 else 100 - heightPercent
                xPercent = (startXPercent + dx).coerceIn(0, maxX)
                yPercent = (startYPercent + dy).coerceIn(0, maxY)
                invalidate()
                onPositionChanged(xPercent, yPercent)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pinchStartDistance = 0f
                dragging = false
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                resizingCorner = -1
                pinchStartDistance = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                if (mode == Mode.VIDEO && event.actionMasked == MotionEvent.ACTION_UP) onVideoPlacementReleased()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
