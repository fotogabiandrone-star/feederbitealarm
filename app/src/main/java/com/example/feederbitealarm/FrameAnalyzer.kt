package com.example.feederbitealarm

import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Sensitivity(
    val minOverlap: Float,
    val borderHitThreshold: Int
) {
    S1(0.50f, 3),
    S2(0.65f, 2),
    S3(0.75f, 2),
    S4(0.85f, 1),
    S5(0.92f, 1)
}

class FrameAnalyzer(
    private val previewView: PreviewView,
    private val getRoi: () -> Rect?,
    private val onMotionDetected: () -> Unit,
    private val onTipData: (List<PointF>, RectF?, PointF?, Boolean) -> Unit,
    private val onDebugData: (Float, Int, Int, Sensitivity) -> Unit
) : ImageAnalysis.Analyzer {

    var sensitivity: Sensitivity = Sensitivity.S3

    private var hsvMin: FloatArray? = null
    private var hsvMax: FloatArray? = null

    private var referencePoints: List<PointF>? = null
    private var referenceBounds: RectF? = null
    private var borderRect: RectF? = null

    private var lastDetectedPoints: List<PointF> = emptyList()

    private var autoSetPending = false

    fun getReferencePoints(): List<PointF>? = referencePoints
    fun getBorderRect(): RectF? = borderRect

    fun resetAll() {
        hsvMin = null
        hsvMax = null
        referencePoints = null
        referenceBounds = null
        borderRect = null
        lastDetectedPoints = emptyList()
        autoSetPending = true
    }
    override fun analyze(image: ImageProxy) {
        val roiView = getRoi() ?: run {
            image.close()
            return
        }

        val roi = mapRoiToImageCoordinates(roiView, image.width, image.height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        // Dacă nu avem HSV, îl detectăm acum
        if (hsvMin == null || hsvMax == null) {
            detectHsvRange(image, roi)
        }

        val minHSV = hsvMin
        val maxHSV = hsvMax
        if (minHSV == null || maxHSV == null) {
            image.close()
            return
        }

        val roiWidth = roi.width()
        val roiHeight = roi.height()

        if (roiWidth < 3 || roiHeight < 3) {
            onTipData(emptyList(), null, null, false)
            onDebugData(0f, 0, 0, sensitivity)
            image.close()
            return
        }

        // extragem luminanța Y
        val yLuma = Array(roiHeight) { IntArray(roiWidth) }

        for (yy in 0 until roiHeight) {
            val yImg = roi.top + yy
            for (xx in 0 until roiWidth) {
                val xImg = roi.left + xx

                val yIndex = yImg * yRowStride + xImg * yPixelStride
                if (yIndex >= yBuffer.limit()) continue

                yLuma[yy][xx] = (yBuffer.get(yIndex).toInt() and 0xFF)
            }
        }

        // Sobel
        val sobelMask = Array(roiHeight) { BooleanArray(roiWidth) }
        val sobelThreshold = 60

        for (yy in 1 until roiHeight - 1) {
            for (xx in 1 until roiWidth - 1) {

                val p00 = yLuma[yy - 1][xx - 1]
                val p01 = yLuma[yy - 1][xx]
                val p02 = yLuma[yy - 1][xx + 1]

                val p10 = yLuma[yy][xx - 1]
                val p11 = yLuma[yy][xx]
                val p12 = yLuma[yy][xx + 1]

                val p20 = yLuma[yy + 1][xx - 1]
                val p21 = yLuma[yy + 1][xx]
                val p22 = yLuma[yy + 1][xx + 1]

                val gx =
                    (-1 * p00) + (1 * p02) +
                            (-2 * p10) + (2 * p12) +
                            (-1 * p20) + (1 * p22)

                val gy =
                    (-1 * p00) + (-2 * p01) + (-1 * p02) +
                            (1 * p20) + (2 * p21) + (1 * p22)

                val mag = abs(gx) + abs(gy)

                if (mag > sobelThreshold) {
                    sobelMask[yy][xx] = true
                }
            }
        }

        val detectedPoints = mutableListOf<PointF>()
        for (yy in 1 until roiHeight - 1) {
            val yImg = roi.top + yy
            for (xx in 1 until roiWidth - 1) {
                if (!sobelMask[yy][xx]) continue

                val xImg = roi.left + xx

                val yIndex = yImg * yRowStride + xImg * yPixelStride
                val uvIndex = (yImg / 2) * uRowStride + (xImg / 2) * uPixelStride

                if (yIndex >= yBuffer.limit() ||
                    uvIndex >= uBuffer.limit() ||
                    uvIndex >= vBuffer.limit()
                ) continue

                val Y = (yBuffer.get(yIndex).toInt() and 0xFF)
                val U = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val V = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                val r = (Y + 1.370705f * V).toInt().coerceIn(0, 255)
                val g = (Y - 0.337633f * U - 0.698001f * V).toInt().coerceIn(0, 255)
                val b = (Y + 1.732446f * U).toInt().coerceIn(0, 255)

                val hsv = rgbToHsv(r, g, b)

                if (!isInRange(hsv, minHSV, maxHSV)) continue

                val viewPoint = mapImageToViewCoordinates(
                    xImg.toFloat(),
                    yImg.toFloat(),
                    image.width,
                    image.height
                )
                detectedPoints.add(viewPoint)
            }
        }

        // 🔥 LIMITARE LA RAMĂ
        if (referenceBounds != null && borderRect != null) {
            val filtered = detectedPoints.filter { p ->
                borderRect!!.contains(p.x, p.y)
            }
            detectedPoints.clear()
            detectedPoints.addAll(filtered)
        }

        lastDetectedPoints = detectedPoints.toList()

        // 🔥 AUTO-SET INSULA DUPĂ TAP
        if (autoSetPending && detectedPoints.size > 30) {
            referencePoints = detectedPoints.toList()

            val minX = detectedPoints.minOf { it.x }
            val maxX = detectedPoints.maxOf { it.x }
            val minY = detectedPoints.minOf { it.y }
            val maxY = detectedPoints.maxOf { it.y }

            referenceBounds = RectF(minX, minY, maxX, maxY)

            val margin = 8f
            borderRect = RectF(
                minX - margin,
                minY - margin,
                maxX + margin,
                maxY + margin
            )

            autoSetPending = false
        }

        if (detectedPoints.isEmpty()) {
            onTipData(emptyList(), null, null, false)
            onDebugData(0f, 0, 0, sensitivity)
            image.close()
            return
        }

        val minX = detectedPoints.minOf { it.x }
        val maxX = detectedPoints.maxOf { it.x }
        val minY = detectedPoints.minOf { it.y }
        val maxY = detectedPoints.maxOf { it.y }

        val boundingBox = RectF(minX, minY, maxX, maxY)

        val cx = detectedPoints.sumOf { it.x.toDouble() } / detectedPoints.size
        val cy = detectedPoints.sumOf { it.y.toDouble() } / detectedPoints.size
        val centroid = PointF(cx.toFloat(), cy.toFloat())

        onTipData(detectedPoints, boundingBox, centroid, false)

        val refPoints = referencePoints
        val refBounds = referenceBounds
        val border = borderRect

        if (refPoints == null || refBounds == null || border == null) {
            onDebugData(0f, 0, detectedPoints.size, sensitivity)
            image.close()
            return
        }

        var overlapCount = 0
        val maxDist = 3f

        for (p in detectedPoints) {
            if (refPoints.any { rp ->
                    val dx = rp.x - p.x
                    val dy = rp.y - p.y
                    dx * dx + dy * dy <= maxDist * maxDist
                }) {
                overlapCount++
            }
        }

        val overlapRatio =
            if (refPoints.isNotEmpty()) overlapCount.toFloat() / refPoints.size.toFloat()
            else 0f

        var borderHits = 0
        for (p in detectedPoints) {
            if (border.contains(p.x, p.y) && !refBounds.contains(p.x, p.y)) {
                borderHits++
            }
        }

        onDebugData(overlapRatio, borderHits, detectedPoints.size, sensitivity)

        val overlapTooLow = overlapRatio < sensitivity.minOverlap
        val borderTooMuch = borderHits >= sensitivity.borderHitThreshold

        if (overlapTooLow || borderTooMuch) {
            onMotionDetected()
        }

        image.close()
    }

    private fun mapRoiToImageCoordinates(roi: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()

        val imageRatio = imageWidth.toFloat() / imageHeight
        val viewRatio = viewWidth / viewHeight

        val scale: Float
        val offsetX: Float
        val offsetY: Float

        if (imageRatio > viewRatio) {
            scale = viewHeight / imageHeight
            val scaledWidth = imageWidth * scale
            offsetX = (scaledWidth - viewWidth) / 2f
            offsetY = 0f
        } else {
            scale = viewWidth / imageWidth
            val scaledHeight = imageHeight * scale
            offsetY = (scaledHeight - viewHeight) / 2f
            offsetX = 0f
        }

        val left = ((roi.left + offsetX) / scale).toInt().coerceIn(0, imageWidth - 1)
        val top = ((roi.top + offsetY) / scale).toInt().coerceIn(0, imageHeight - 1)
        val right = ((roi.right + offsetX) / scale).toInt().coerceIn(1, imageWidth)
        val bottom = ((roi.bottom + offsetY) / scale).toInt().coerceIn(1, imageHeight)

        return Rect(left, top, right, bottom)
    }

    private fun mapImageToViewCoordinates(
        x: Float,
        y: Float,
        imageWidth: Int,
        imageHeight: Int
    ): PointF {
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()

        val imageRatio = imageWidth.toFloat() / imageHeight
        val viewRatio = viewWidth / viewHeight

        val scale: Float
        val offsetX: Float
        val offsetY: Float

        return if (imageRatio > viewRatio) {
            scale = viewHeight / imageHeight
            val scaledWidth = imageWidth * scale
            offsetX = (scaledWidth - viewWidth) / 2f
            offsetY = 0f
            PointF(x * scale - offsetX, y * scale - offsetY)
        } else {
            scale = viewWidth / imageWidth
            val scaledHeight = imageHeight * scale
            offsetY = (scaledHeight - viewHeight) / 2f
            offsetX = 0f
            PointF(x * scale - offsetX, y * scale - offsetY)
        }
    }

    private fun detectHsvRange(image: ImageProxy, roi: Rect) {
        val samplePixels = mutableListOf<FloatArray>()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (y in roi.top until roi.bottom step 4) {
            for (x in roi.left until roi.right step 4) {

                val yIndex = y * yRowStride + x * yPixelStride
                val uvIndex = (y / 2) * uRowStride + (x / 2) * uPixelStride

                if (yIndex >= yBuffer.limit() ||
                    uvIndex >= uBuffer.limit() ||
                    uvIndex >= vBuffer.limit()
                ) continue

                val Y = (yBuffer.get(yIndex).toInt() and 0xFF)
                val U = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val V = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                val r = (Y + 1.370705f * V).toInt().coerceIn(0, 255)
                val g = (Y - 0.337633f * U - 0.698001f * V).toInt().coerceIn(0, 255)
                val b = (Y + 1.732446f * U).toInt().coerceIn(0, 255)

                samplePixels.add(rgbToHsv(r, g, b))
            }
        }

        if (samplePixels.isEmpty()) return

        val hValues = samplePixels.map { it[0] }
        val sValues = samplePixels.map { it[1] }
        val vValues = samplePixels.map { it[2] }

        hsvMin = floatArrayOf(
            hValues.minOrNull()!! - 5f,
            (sValues.minOrNull()!! - 0.1f).coerceAtLeast(0f),
            (vValues.minOrNull()!! - 0.1f).coerceAtLeast(0f)
        )

        hsvMax = floatArrayOf(
            hValues.maxOrNull()!! + 5f,
            (sValues.maxOrNull()!! + 0.1f).coerceAtMost(1f),
            (vValues.maxOrNull()!! + 0.1f).coerceAtMost(1f)
        )
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f

        val max = max(rf, max(gf, bf))
        val min = min(rf, min(gf, bf))
        val delta = max - min

        val h = when {
            delta == 0f -> 0f
            max == rf -> ((gf - bf) / delta) % 6f
            max == gf -> ((bf - rf) / delta) + 2f
            else -> ((rf - gf) / delta) + 4f
        } * 60f

        val s = if (max == 0f) 0f else delta / max
        val v = max

        return floatArrayOf((h + 360f) % 360f, s, v)
    }

    private fun isInRange(hsv: FloatArray, min: FloatArray, max: FloatArray): Boolean {
        return hsv[0] in min[0]..max[0] &&
                hsv[1] in min[1]..max[1] &&
                hsv[2] in min[2]..max[2]
    }
}
