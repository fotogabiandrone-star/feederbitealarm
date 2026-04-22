package com.example.feederbitealarm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var roiOverlay: RoiOverlayView
    private lateinit var tipOverlay: TipOverlayView
    private lateinit var debugOverlay: DebugOverlayView

    private lateinit var btnS1: Button
    private lateinit var btnS2: Button
    private lateinit var btnS3: Button
    private lateinit var btnS4: Button

    private lateinit var btnZoom1: Button
    private lateinit var btnZoom3: Button
    private lateinit var btnZoom6: Button
    private lateinit var btnZoom10: Button

    private lateinit var btnSetTip: Button

    private lateinit var frameAnalyzer: FrameAnalyzer
    private var camera: Camera? = null

    private val cameraPermission = Manifest.permission.CAMERA
    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        roiOverlay = findViewById(R.id.roiOverlay)
        tipOverlay = findViewById(R.id.tipOverlay)
        debugOverlay = findViewById(R.id.debugOverlay)

        btnS1 = findViewById(R.id.btnS1)
        btnS2 = findViewById(R.id.btnS2)
        btnS3 = findViewById(R.id.btnS3)
        btnS4 = findViewById(R.id.btnS4)

        btnZoom1 = findViewById(R.id.btnZoom1)
        btnZoom3 = findViewById(R.id.btnZoom3)
        btnZoom6 = findViewById(R.id.btnZoom6)
        btnZoom10 = findViewById(R.id.btnZoom10)

        btnSetTip = findViewById(R.id.btnSetTip)

        frameAnalyzer = FrameAnalyzer(
            previewView = previewView,
            getRoi = { roiOverlay.getRoiRect() },
            onMotionDetected = {
                tipOverlay.updateTipData(emptyList(), null, null, true)
            },
            onTipData = { points, box, centroid, flash ->
                tipOverlay.updateTipData(points, box, centroid, flash)

                frameAnalyzer.getReferencePoints()?.let { ref ->
                    tipOverlay.updateReference(ref, frameAnalyzer.getBorderRect())
                }
            },
            onDebugData = { overlap, borderHits, count, sens ->
                debugOverlay.update(overlap, borderHits, count, sens)
            }
        )

        setupSensitivityButtons()
        setupZoomButtons()
        setupTapToFocus()
        setupSetTipButton()

        if (hasCameraPermission()) startCamera()
        else requestCameraPermission()
    }

    private fun setupSensitivityButtons() {
        btnS1.setOnClickListener { frameAnalyzer.sensitivity = Sensitivity.S1 }
        btnS2.setOnClickListener { frameAnalyzer.sensitivity = Sensitivity.S2 }
        btnS3.setOnClickListener { frameAnalyzer.sensitivity = Sensitivity.S3 }
        btnS4.setOnClickListener { frameAnalyzer.sensitivity = Sensitivity.S4 }
    }

    private fun setupZoomButtons() {
        btnZoom1.setOnClickListener { camera?.cameraControl?.setZoomRatio(1f) }
        btnZoom3.setOnClickListener { camera?.cameraControl?.setZoomRatio(3f) }
        btnZoom6.setOnClickListener { camera?.cameraControl?.setZoomRatio(6f) }
        btnZoom10.setOnClickListener { camera?.cameraControl?.setZoomRatio(10f) }
    }

    private fun setupTapToFocus() {
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {

                previewView.performClick()

                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)

                val action = FocusMeteringAction.Builder(point).build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }
    }

    private fun setupSetTipButton() {
        btnSetTip.setOnClickListener {

            val cx = roiOverlay.roiCenterX
            val cy = roiOverlay.roiCenterY

            val factory = previewView.meteringPointFactory
            val point = factory.createPoint(cx, cy)

            val action = FocusMeteringAction.Builder(point).build()
            camera?.cameraControl?.startFocusAndMetering(action)

            frameAnalyzer.resetAll()
            frameAnalyzer.requestAutoSet()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        ContextCompat.getMainExecutor(this),
                        frameAnalyzer
                    )
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                analysis
            )

        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, cameraPermission) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(cameraPermission), requestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == this.requestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }
}
