package com.lupa.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
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
import java.util.concurrent.TimeUnit
import kotlin.math.atan2

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var frozenHintChip: View
    private lateinit var panelBrightness: View
    private lateinit var brightnessSlider: SeekBar
    private lateinit var zoomSlider: SeekBar
    private lateinit var btnFlash: MaterialButton
    private lateinit var btnBrightness: MaterialButton
    private lateinit var btnFreeze: MaterialButton
    private lateinit var btnCapture: MaterialButton

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private var frozen = false

    private var frozenBitmap: Bitmap? = null
    private var frozenScale = 1.0f
    private var frozenTranslateX = 0f
    private var frozenTranslateY = 0f
    private var frozenRotation = 0f
    private var lastRotationAngle = 0f
    private var isRotating = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private var brightnessLevel = 50

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
        frozenHintChip = findViewById(R.id.frozenHintChip)
        panelBrightness = findViewById(R.id.panelBrightness)
        brightnessSlider = findViewById(R.id.brightnessSlider)
        zoomSlider = findViewById(R.id.zoomSlider)
        btnFlash = findViewById(R.id.btnFlash)
        btnBrightness = findViewById(R.id.btnBrightness)
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

        setupInteractions()
        setupButtons()
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
            applyLiveBrightness()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setupInteractions() {
        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (frozen) {
                    frozenScale = 1.0f + (progress / 100f) * 5.0f
                    applyFrozenTransform()
                } else {
                    val cam = camera ?: return
                    val min = cam.cameraInfo.zoomState.value?.minZoomRatio ?: return
                    val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: return
                    cam.cameraControl.setZoomRatio(min + (max - min) * progress / 100f)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        brightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                brightnessLevel = progress
                if (frozen) {
                    applyFrozenBrightness()
                } else {
                    applyLiveBrightness()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val livePinchDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: return false
                    cam.cameraControl.setZoomRatio(current * detector.scaleFactor)
                    updateZoomSliderRange()
                    return true
                }
            })

        val liveGestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val cam = camera ?: return false
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    return true
                }
            })

        previewView.setOnTouchListener { _, event ->
            livePinchDetector.onTouchEvent(event)
            liveGestureDetector.onTouchEvent(event)
            true
        }

        val frozenPinchDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    frozenScale = (frozenScale * detector.scaleFactor).coerceIn(1.0f, 6.0f)
                    applyFrozenTransform()
                    updateFrozenSliderRange()
                    return true
                }
            })

        frozenView.setOnTouchListener { _, event ->
            frozenPinchDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    isDragging = true
                    isRotating = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        val dx = event.getX(1) - event.getX(0)
                        val dy = event.getY(1) - event.getY(0)
                        lastRotationAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        isRotating = true
                        isDragging = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 2 && isRotating) {
                        val dx = event.getX(1) - event.getX(0)
                        val dy = event.getY(1) - event.getY(0)
                        val currentAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        val delta = currentAngle - lastRotationAngle
                        frozenRotation = (frozenRotation + delta) % 360f
                        lastRotationAngle = currentAngle
                        applyFrozenTransform()
                    } else if (event.pointerCount == 1 && isDragging && frozenScale > 1.0f) {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        frozenTranslateX += dx
                        frozenTranslateY += dy
                        applyFrozenTransform()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        isRotating = false
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        lastTouchX = event.getX(remainingIndex)
                        lastTouchY = event.getY(remainingIndex)
                        isDragging = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    isRotating = false
                }
            }
            true
        }
    }

    private fun applyLiveBrightness() {
        val cam = camera ?: return
        val exposure = cam.cameraInfo.exposureState
        if (exposure.isExposureCompensationSupported) {
            val range = exposure.exposureCompensationRange
            val index = (range.lower + (range.upper - range.lower) * (brightnessLevel / 100f)).toInt()
            cam.cameraControl.setExposureCompensationIndex(index)
        }
    }

    private fun applyFrozenBrightness() {
        val offset = (brightnessLevel - 50) * 2.55f
        val cm = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, offset,
            0f, 1f, 0f, 0f, offset,
            0f, 0f, 1f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        frozenView.colorFilter = ColorMatrixColorFilter(cm)
    }

    private fun applyFrozenTransform() {
        val maxTranslateX = (frozenView.width * (frozenScale - 1f)) / 2f
        val maxTranslateY = (frozenView.height * (frozenScale - 1f)) / 2f
        frozenTranslateX = if (maxTranslateX > 0) frozenTranslateX.coerceIn(-maxTranslateX, maxTranslateX) else 0f
        frozenTranslateY = if (maxTranslateY > 0) frozenTranslateY.coerceIn(-maxTranslateY, maxTranslateY) else 0f

        frozenView.scaleX = frozenScale
        frozenView.scaleY = frozenScale
        frozenView.translationX = frozenTranslateX
        frozenView.translationY = frozenTranslateY
        frozenView.rotation = frozenRotation
    }

    private fun updateFrozenSliderRange() {
        val progress = ((frozenScale - 1.0f) / 5.0f * 100).toInt().coerceIn(0, 100)
        zoomSlider.progress = progress
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
            updateFlashUi()
        }

        btnBrightness.setOnClickListener {
            val isVisible = panelBrightness.visibility == View.VISIBLE
            panelBrightness.visibility = if (isVisible) View.GONE else View.VISIBLE
            if (!isVisible) {
                btnBrightness.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.blue_active))
                btnBrightness.iconTint =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black))
            } else {
                btnBrightness.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.btn_idle))
                btnBrightness.iconTint =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
            }
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

    private fun updateFlashUi() {
        if (torchOn) {
            btnFlash.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.amber_active))
            btnFlash.iconTint =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black))
        } else {
            btnFlash.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.btn_idle))
            btnFlash.iconTint =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.white))
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
                            frozenBitmap?.recycle()
                            frozenBitmap = bitmap
                            frozenView.setImageBitmap(bitmap)
                            frozenView.visibility = View.VISIBLE
                            frozenHintChip.visibility = View.VISIBLE
                            frozen = true
                            frozenScale = 1.0f
                            frozenTranslateX = 0f
                            frozenTranslateY = 0f
                            frozenRotation = 0f
                            frozenView.scaleX = 1.0f
                            frozenView.scaleY = 1.0f
                            frozenView.translationX = 0f
                            frozenView.translationY = 0f
                            frozenView.rotation = 0f
                            applyFrozenBrightness()
                            zoomSlider.progress = 0
                            btnFreeze.setIconResource(R.drawable.ic_play)
                            btnFreeze.backgroundTintList =
                                ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.blue_active))
                            btnFreeze.iconTint =
                                ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.black))
                        }
                    }
                }
            })
    }

    private fun unfreeze() {
        frozenBitmap?.recycle()
        frozenBitmap = null
        frozenView.setImageDrawable(null)
        frozenView.colorFilter = null
        frozenView.visibility = View.GONE
        frozenHintChip.visibility = View.GONE
        frozen = false
        frozenScale = 1.0f
        frozenTranslateX = 0f
        frozenTranslateY = 0f
        frozenRotation = 0f
        frozenView.scaleX = 1.0f
        frozenView.scaleY = 1.0f
        frozenView.translationX = 0f
        frozenView.translationY = 0f
        frozenView.rotation = 0f
        btnFreeze.setIconResource(R.drawable.ic_freeze)
        btnFreeze.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
        btnFreeze.iconTint =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.black))
        updateZoomSliderRange()
    }

    private fun captureToGallery() {
        if (frozen && frozenBitmap != null) {
            val transformed = getTransformedFrozenBitmap()
            saveBitmapToGallery(transformed)
            return
        }

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

    private fun getTransformedFrozenBitmap(): Bitmap {
        val base = frozenBitmap ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val matrix = android.graphics.Matrix()
        if (frozenRotation != 0f) {
            matrix.postRotate(frozenRotation)
        }
        val rotated = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
        if (brightnessLevel == 50) return rotated

        val result = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint()
        val offset = (brightnessLevel - 50) * 2.55f
        val cm = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, offset,
            0f, 1f, 0f, 0f, offset,
            0f, 0f, 1f, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(rotated, 0f, 0f, paint)
        if (rotated != base) rotated.recycle()
        return result
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val name = "Lupa_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lupa")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Error al guardar imagen", Toast.LENGTH_SHORT).show()
            return
        }
        cameraExecutor.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
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
        frozenBitmap?.recycle()
        frozenBitmap = null
        cameraExecutor.shutdown()
    }
}
