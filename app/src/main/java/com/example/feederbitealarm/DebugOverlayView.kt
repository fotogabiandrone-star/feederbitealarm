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
        color = Color.WHITE
        textSize = 62f
        isAntiAlias = true
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

        var y = 50f
        canvas.drawText("Overlap: ${(overlap * 100).toInt()}%", 20f, y, paint); y += 45f
        canvas.drawText("Border hits: $borderHits", 20f, y, paint); y += 45f
        canvas.drawText("Points: $count", 20f, y, paint); y += 45f
        canvas.drawText("Sens: ${sensitivity.name}", 20f, y, paint)
    }
}
