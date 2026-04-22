package com.example.feederbitealarm

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class TipOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val tipPoints = mutableListOf<PointF>()
    private val referencePoints = mutableListOf<PointF>()
    private var borderRect: RectF? = null
    private var tipBoundingBox: RectF? = null
    private var tipCentroid: PointF? = null
    private var flashActive = false
    private var flashAlpha = 0

    private val pointsPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val referencePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val hatchPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val contourPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val centroidPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val flashPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    fun updateTipData(
        points: List<PointF>,
        boundingBox: RectF?,
        centroid: PointF?,
        triggerFlash: Boolean = false
    ) {
        tipPoints.clear()
        tipPoints.addAll(points)

        tipBoundingBox = boundingBox
        tipCentroid = centroid

        if (triggerFlash) {
            flashActive = true
            flashAlpha = 255
        }

        invalidate()
    }

    fun updateReference(points: List<PointF>, border: RectF?) {
        referencePoints.clear()
        referencePoints.addAll(points)
        borderRect = border
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // INSULĂ – contur
        if (referencePoints.size > 2) {
            val path = Path()
            path.moveTo(referencePoints[0].x, referencePoints[0].y)
            for (i in 1 until referencePoints.size) {
                path.lineTo(referencePoints[i].x, referencePoints[i].y)
            }
            path.close()
            canvas.drawPath(path, referencePaint)
        }

        // HASURĂ în RAMĂ
        borderRect?.let { b ->
            var y = b.top
            while (y < b.bottom) {
                canvas.drawLine(b.left, y, b.right, y + 10f, hatchPaint)
                y += 6f
            }
        }

        // puncte detectate
        for (p in tipPoints) {
            canvas.drawCircle(p.x, p.y, 3f, pointsPaint)
        }

        // bounding box curent
        tipBoundingBox?.let { canvas.drawRect(it, contourPaint) }

        // centroid
        tipCentroid?.let { c ->
            canvas.drawLine(c.x, 0f, c.x, height.toFloat(), centroidPaint)
        }

        // flash
        if (flashActive && flashAlpha > 0) {
            flashPaint.alpha = flashAlpha
            val inset = 8f
            canvas.drawRect(
                inset,
                inset,
                width - inset,
                height - inset,
                flashPaint
            )
            flashAlpha -= 25
            if (flashAlpha > 0) postInvalidateOnAnimation()
            else flashActive = false
        }
    }
}
