package com.lupa.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var zoomSlider: SeekBar
    private lateinit var btnFlash: MaterialButton
    private lateinit var btnFreeze: MaterialButton
    private lateinit var btnCapture: MaterialButton

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private var frozen = false

    private lateinit var cameraExecutor: ExecutorService

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        frozenView = findViewById(R.id.frozenView)
        zoomSlider = findViewById(R.id.zoomSlider)
        btnFlash = findViewById(R.id.btnFlash)
        btnFreeze = findViewById(R.id.btnFreeze)
        btnCapture = findViewById(R.id.btnCapture)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setupZoom()
        setupButtons()
        setupTapToFocus()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )

            val cam = camera ?: return@addListener
            btnFlash.visibility =
                if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
            updateZoomSliderRange()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupZoom() {
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val cam = camera ?: return
                val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: return
                val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: return
                cam.cameraControl.setZoomRatio(min + (max - min) * progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val pinch = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: return false
                    cam.cameraControl.setZoomRatio(current * detector.scaleFactor)
                    return true
                }
            })
        previewView.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            false
        }
    }

    private fun updateZoomSliderRange() {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        zoomSlider.progress =
            ((state.zoomRatio - state.minZoomRatio) / (state.maxZoomRatio - state.minZoomRatio) * 100)
                .toInt().coerceIn(0, 100)
    }

    private fun setupButtons() {
        btnFlash.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            btnFlash.text = getString(R.string.flash) + if (torchOn) " ON" else ""
        }

        btnFreeze.setOnClickListener {
            if (frozen) {
                unfreeze()
            } else {
                freeze()
            }
        }

        btnCapture.setOnClickListener { captureToGallery() }
    }

    private fun setupTapToFocus() {
        previewView.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            val point = previewView.meteringPointFactory.createPoint(
                previewView.width / 2f, previewView.height / 2f
            )
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point).build()
            )
        }
    }

    private fun freeze() {
        imageCapture?.takePicture(cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    runOnUiThread {
                        if (bitmap != null) {
                            frozenView.setImageBitmap(bitmap)
                            frozenView.visibility = View.VISIBLE
                            frozen = true
                            btnFreeze.text = getString(R.string.unfreeze)
                        }
                    }
                }
            })
    }

    private fun unfreeze() {
        frozenView.setImageDrawable(null)
        frozenView.visibility = View.GONE
        frozen = false
        btnFreeze.text = getString(R.string.freeze)
    }

    private fun captureToGallery() {
        val name = "Lupa_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lupa")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        imageCapture?.takePicture(output, cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, exc.message, Toast.LENGTH_SHORT).show()
                    }
                }
            })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
