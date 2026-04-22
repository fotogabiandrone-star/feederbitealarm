package com.example.feederbitealarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Poziția țintei (centrul)
    var roiCenterX = 300f
    var roiCenterY = 300f

    // Dimensiunea țintei (raza cercului)
    var roiRadius = 12f   // diametru 24px – perfect pentru vârf

    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    fun getRoiRect(): Rect {
        val r = roiRadius.toInt()
        return Rect(
            (roiCenterX - r).toInt(),
            (roiCenterY - r).toInt(),
            (roiCenterX + r).toInt(),
            (roiCenterY + r).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = roiCenterX
        val cy = roiCenterY
        val r = roiRadius
        val extra = 4f   // cât ies brațele crucii în afara cercului

        // Cerc
        canvas.drawCircle(cx, cy, r, paint)

        // Cruce verticală
        canvas.drawLine(
            cx,
            cy - r - extra,
            cx,
            cy + r + extra,
            paint
        )

        // Cruce orizontală
        canvas.drawLine(
            cx - r - extra,
            cy,
            cx + r + extra,
            cy,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                // Mutăm ținta exact unde tragi cu degetul
                roiCenterX = event.x
                roiCenterY = event.y
                invalidate()
                return true
            }
        }
        return false
    }
}
