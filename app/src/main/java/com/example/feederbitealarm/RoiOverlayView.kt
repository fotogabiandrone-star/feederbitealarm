package com.example.feederbitealarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var roiRect: Rect? = null

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        roiRect?.let {
            val cx = it.exactCenterX()
            val cy = it.exactCenterY()
            canvas.drawCircle(cx, cy, 20f, paint)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
