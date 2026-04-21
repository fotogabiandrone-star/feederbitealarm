package com.example.feederbitealarm

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.ImageAnalysis

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var roiOverlay: RoiOverlayView
    private lateinit var btnZoom1x: Button
    private lateinit var btnZoom3x: Button

    private var camera: androidx.camera.core.Camera? = null

    private val cameraPermission = Manifest.permission.CAMERA
    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        roiOverlay = findViewById(R.id.roiOverlay)
        btnZoom1x = findViewById(R.id.btnZoom1x)
        btnZoom3x = findViewById(R.id.btnZoom3x)

        // ROI mutabil continuu
        roiOverlay.setOnTouchListener { v, event ->

            val size = 120

            when (event.action) {

                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {

                    val left = (event.x - size / 2).toInt()
                    val top = (event.y - size / 2).toInt()
                    val right = (event.x + size / 2).toInt()
                    val bottom = (event.y + size / 2).toInt()

                    roiOverlay.roiRect = Rect(left, top, right, bottom)
                    roiOverlay.invalidate()

                    if (event.action == MotionEvent.ACTION_DOWN) {
                        lockFocusOnRoi()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    v.performClick()
                }
            }

            true
        }

        btnZoom1x.setOnClickListener { setZoom1x() }
        btnZoom3x.setOnClickListener { setZoom3x() }

        if (ContextCompat.checkSelfPermission(this, cameraPermission)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(cameraPermission),
                requestCode
            )
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(
                        ContextCompat.getMainExecutor(this),
                        FrameAnalyzer(
                            previewView = previewView,
                            getRoi = { roiOverlay.roiRect },
                            onMotionDetected = { onMotionDetected() },
                            sensitivityPx = 5
                        )
                    )
                }

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                camera?.cameraControl?.setZoomRatio(1.0f)

            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom1x() {
        camera?.cameraControl?.setZoomRatio(1.0f)
    }

    private fun setZoom3x() {
        camera?.cameraControl?.setZoomRatio(3.0f)
    }

    private fun getRoiCenterPoint(): MeteringPoint? {
        val rect = roiOverlay.roiRect ?: return null
        val factory = previewView.meteringPointFactory
        return factory.createPoint(rect.exactCenterX(), rect.exactCenterY())
    }

    private fun lockFocusOnRoi() {
        val point = getRoiCenterPoint() ?: return

        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF
        )
            .disableAutoCancel()
            .build()

        camera?.cameraControl?.startFocusAndMetering(action)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == this.requestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            startCamera()
        }
    }

    private fun onMotionDetected() {
        runOnUiThread {
            println("MISCARE DETECTATA")
        }
    }
}

/**
 * View custom pentru desenarea ROI-ului
 */
class RoiOverlayView(context: android.content.Context, attrs: android.util.AttributeSet) :
    View(context, attrs) {

    var roiRect: Rect? = null
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        roiRect?.let { canvas.drawRect(it, paint) }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
