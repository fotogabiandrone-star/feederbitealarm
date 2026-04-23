package com.example.feederbitealarm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview

@OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
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
        debugListCameras()

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
                // doar flash pe overlay când detectăm mișcare
                tipOverlay.updateTipData(emptyList(), null, null, true)
            },
            onTipData = { _, _, _, _ ->
                // nu mai desenăm nimic (fără flood-fill, fără centroid, fără box)
            },
            onDebugData = { overlap, borderHits, count, sens ->
                // overlap și borderHits nu mai au sens, dar folosim count ca "energie"
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
        btnZoom10.setOnClickListener { camera?.cameraControl?.setZoomRatio(8f) }
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
            // în varianta B nu mai avem resetAll / requestAutoSet
        }
    }

    private fun debugListCameras() {
        val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)

        for (id in cm.cameraIdList) {
            val chars = cm.getCameraCharacteristics(id)

            val facing = when (chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                else -> "UNKNOWN"
            }

            val focals = chars.get(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.joinToString(", ")

            val apertures = chars.get(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
            )?.joinToString(", ")

            val capabilities = chars.get(
                android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )?.joinToString(", ")

            val configs = chars.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val yuvSizes = configs
                ?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?.joinToString { "${it.width}x${it.height}" }

            Log.e("CAM_DEBUG", "----------------------------------------")
            Log.e("CAM_DEBUG", "Camera ID: $id")
            Log.e("CAM_DEBUG", "Facing: $facing")
            Log.e("CAM_DEBUG", "Focal lengths: $focals")
            Log.e("CAM_DEBUG", "Apertures: $apertures")
            Log.e("CAM_DEBUG", "Capabilities: $capabilities")
            Log.e("CAM_DEBUG", "YUV sizes: $yuvSizes")
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val cameraManager = getSystemService(android.hardware.camera2.CameraManager::class.java)

            var wideCameraId: String? = null

            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)

                if (facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    val focals = chars.get(
                        android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    ) ?: continue

                    if (focals.any { it == 5.4f }) {
                        wideCameraId = id
                        break
                    }
                }
            }

            val cameraSelector = CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { info ->
                        val cam2 = Camera2CameraInfo.from(info)
                        cam2.cameraId == wideCameraId
                    }
                }
                .build()

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

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                analysis
            )

            camera?.cameraInfo?.zoomState?.observe(this) { state ->
                Log.d("ZOOM", "max zoom = ${state.maxZoomRatio}")
            }

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
