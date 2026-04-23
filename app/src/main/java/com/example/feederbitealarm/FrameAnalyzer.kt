package com.example.feederbitealarm

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import kotlin.math.abs

enum class Sensitivity(
    val motionThreshold: Int
) {
    S1(3000),
    S2(5000),
    S3(8000),
    S4(12000)
}

class FrameAnalyzer(
    private val previewView: PreviewView,
    private val getRoi: () -> Rect?,
    private val onMotionDetected: () -> Unit,
    private val onTipData: (List<PointF>, RectF?, PointF?, Boolean) -> Unit,
    private val onDebugData: (Float, Int, Int, Sensitivity) -> Unit
) : ImageAnalysis.Analyzer {

    var sensitivity: Sensitivity = Sensitivity.S3

    private var prevY: ByteArray? = null

    override fun analyze(image: ImageProxy) {
        val roiView = getRoi() ?: run { image.close(); return }

        // convert ROI from view coords to image coords
        val roi = mapRoiToImageCoordinates(roiView, image.width, image.height)

        // reduce ROI to 20x20 px around center
        val cx = (roi.left + roi.right) / 2
        val cy = (roi.top + roi.bottom) / 2
        val half = 10

        val left = (cx - half).coerceIn(0, image.width - 1)
        val right = (cx + half).coerceIn(0, image.width - 1)
        val top = (cy - half).coerceIn(0, image.height - 1)
        val bottom = (cy + half).coerceIn(0, image.height - 1)

        // extract Y plane
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val ySize = yBuf.remaining()

        val currentY = ByteArray(ySize)
        yBuf.get(currentY)
        yBuf.rewind()

        var motionEnergy = 0

        prevY?.let { prev ->
            val w = image.width

            for (yy in top until bottom) {
                val row = yy * w
                for (xx in left until right) {
                    val idx = row + xx
                    if (idx < prev.size && idx < currentY.size) {
                        motionEnergy += abs(
                            (currentY[idx].toInt() and 0xFF) -
                                    (prev[idx].toInt() and 0xFF)
                        )
                    }
                }
            }
        }

        prevY = currentY

        // debug info
        onDebugData(
            0f,              // overlap (nu mai folosim)
            0,               // borderHits (nu mai folosim)
            motionEnergy,    // afișăm energia ca "points"
            sensitivity
        )

        // trigger
        if (motionEnergy > sensitivity.motionThreshold) {
            onMotionDetected()
        }

        // no tip data (fără flood-fill)
        onTipData(emptyList(), null, null, false)

        image.close()
    }

    private fun mapRoiToImageCoordinates(roi: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val vw = previewView.width.toFloat()
        val vh = previewView.height.toFloat()
        val ir = imageWidth.toFloat() / imageHeight
        val vr = vw / vh

        val scale: Float
        val ox: Float
        val oy: Float

        if (ir > vr) {
            scale = vh / imageHeight
            val sw = imageWidth * scale
            ox = (sw - vw) / 2f
            oy = 0f
        } else {
            scale = vw / imageWidth
            val sh = imageHeight * scale
            oy = (sh - vh) / 2f
            ox = 0f
        }

        val l = ((roi.left + ox) / scale).toInt().coerceIn(0, imageWidth - 1)
        val t = ((roi.top + oy) / scale).toInt().coerceIn(0, imageHeight - 1)
        val r = ((roi.right + ox) / scale).toInt().coerceIn(1, imageWidth)
        val b = ((roi.bottom + oy) / scale).toInt().coerceIn(1, imageHeight)

        return Rect(l, t, r, b)
    }
}
