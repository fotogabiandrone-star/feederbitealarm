package com.example.feederbitealarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class DebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var overlap: Float = 0f
    private var borderHits: Int = 0
    private var count: Int = 0
    private var sensitivity: Sensitivity = Sensitivity.S3

    private val paint = Paint().apply {
        color = Color.rgb(180, 20, 20)   // roșu închis
        textSize = 80f
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(140, 255, 255, 255)   // negru semi-transparent
    }


    fun update(
        overlap: Float,
        borderHits: Int,
        count: Int,
        sensitivity: Sensitivity
    ) {
        this.overlap = overlap
        this.borderHits = borderHits
        this.count = count
        this.sensitivity = sensitivity
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // dimensiunea blocului de text
        val left = 10f
        val top = 10f
        val right = 420f
        val bottom = 10f + 4 * 45f + 20f   // 4 linii + padding

        // fundal semitransparent
        canvas.drawRect(left, top, right, bottom, bgPaint)

        // text roșu închis
        var y = top + 45f
        canvas.drawText("Overlap: ${(overlap * 100).toInt()}%", left + 10f, y, paint); y += 80f
        canvas.drawText("Border hits: $borderHits", left + 10f, y, paint); y += 80f
        canvas.drawText("Points: $count", left + 10f, y, paint); y += 80f
        canvas.drawText("Sens: ${sensitivity.name}", left + 10f, y, paint)
    }
}
