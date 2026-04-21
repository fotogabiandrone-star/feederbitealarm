package com.example.feederbitealarm

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FrameAnalyzer(
    private val previewView: PreviewView,
    private val getRoi: () -> Rect?,
    private val onMotionDetected: () -> Unit,
    private val sensitivityPx: Int = 5
) : ImageAnalysis.Analyzer {

    private var lastDominantColumn: Int? = null

    // Interval HSV detectat automat din vârf
    private var hsvMin: FloatArray? = null
    private var hsvMax: FloatArray? = null

    override fun analyze(image: ImageProxy) {
        val roiView = getRoi() ?: run {
            image.close()
            return
        }

        // 1. Mapare ROI (din View) în coordonate imagine
        val roi = mapRoiToImageCoordinates(roiView, image.width, image.height)

        // 2. Plane-uri YUV
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

        // 3. Detectăm intervalul HSV al vârfului (o singură dată)
        if (hsvMin == null || hsvMax == null) {
            detectHsvRange(image, roi)
        }

        val minHSV = hsvMin
        val maxHSV = hsvMax
        if (minHSV == null || maxHSV == null) {
            image.close()
            return
        }

        // 4. Calculăm coloana dominantă în ROI
        val columnScores = IntArray(roi.width())

        for (y in roi.top until roi.bottom) {
            for (x in roi.left until roi.right) {

                val yIndex = y * yRowStride + x * yPixelStride
                val uvIndex = (y / 2) * uRowStride + (x / 2) * uPixelStride

                if (yIndex >= yBuffer.limit() ||
                    uvIndex >= uBuffer.limit() ||
                    uvIndex >= vBuffer.limit()
                ) continue

                val Y = (yBuffer.get(yIndex).toInt() and 0xFF)
                val U = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                val V = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                // YUV → RGB
                val r = (Y + 1.370705f * V).toInt().coerceIn(0, 255)
                val g = (Y - 0.337633f * U - 0.698001f * V).toInt().coerceIn(0, 255)
                val b = (Y + 1.732446f * U).toInt().coerceIn(0, 255)

                // RGB → HSV
                val hsv = rgbToHsv(r, g, b)

                if (isInRange(hsv, minHSV, maxHSV)) {
                    val colIndex = x - roi.left
                    columnScores[colIndex]++
                }
            }
        }

        // 5. Găsim coloana dominantă
        var maxScore = 0
        var dominantColIndex = -1

        for (i in columnScores.indices) {
            if (columnScores[i] > maxScore) {
                maxScore = columnScores[i]
                dominantColIndex = i
            }
        }

        if (dominantColIndex != -1) {
            val currentColumn = roi.left + dominantColIndex
            val last = lastDominantColumn

            if (last != null) {
                val delta = abs(currentColumn - last)
                if (delta >= sensitivityPx) {
                    onMotionDetected()
                }
            }

            lastDominantColumn = currentColumn
        }

        image.close()
    }

    // === Mapare ROI View → coordonate imagine (FILL_CENTER corect) ===
    private fun mapRoiToImageCoordinates(roi: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()

        val imageRatio = imageWidth.toFloat() / imageHeight
        val viewRatio = viewWidth / viewHeight

        val scale: Float
        val offsetX: Float
        val offsetY: Float

        if (imageRatio > viewRatio) {
            // imaginea e mai lată → se decupează pe X
            scale = viewHeight / imageHeight
            val scaledWidth = imageWidth * scale
            offsetX = (scaledWidth - viewWidth) / 2f
            offsetY = 0f
        } else {
            // imaginea e mai înaltă → se decupează pe Y
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

    // === Detectăm automat intervalul HSV al vârfului din ROI ===
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
