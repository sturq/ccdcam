package at.sturq.ccdcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import at.sturq.ccdcam.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO, EDIT }

    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: CcdRenderer
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoRecorder: VideoRecorder? = null
    private var recordingFile: java.io.File? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null

    private var mode = Mode.VIDEO
    private var recordStartMs = 0L
    private var blinkOn = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startCamera()
        else Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show()
    }

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        binding.captureBtn.isEnabled = false
        Toast.makeText(this, "Processing...", Toast.LENGTH_SHORT).show()
        ioScope.launch {
            try {
                val out = PhotoFilter.process(this@MainActivity, uri)
                if (out == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Could not decode", Toast.LENGTH_LONG).show()
                        binding.captureBtn.isEnabled = true
                    }
                    return@launch
                }
                val savedUri = saveImage(out)
                withContext(Dispatchers.Main) {
                    binding.photoPreview.setImageBitmap(out)
                    binding.photoPreview.visibility = View.VISIBLE
                    Toast.makeText(
                        this@MainActivity,
                        if (savedUri != null) "Saved to Pictures/CCDCam" else "Save failed",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.captureBtn.isEnabled = true
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                    binding.captureBtn.isEnabled = true
                }
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (videoRecorder != null) {
                val elapsed = SystemClock.elapsedRealtime() - recordStartMs
                binding.timecode.text = formatTimecode(elapsed)
                blinkOn = !blinkOn
                binding.recDot.visibility = if (blinkOn) View.VISIBLE else View.INVISIBLE
                uiHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.glView.setEGLContextClientVersion(2)
        renderer = CcdRenderer(this) { st ->
            pendingSurfaceTexture = st
            runOnUiThread { startCamera() }
        }
        binding.glView.setRenderer(renderer)
        binding.glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        binding.dateTxt.text = SimpleDateFormat("yyyy MM dd", Locale.US)
            .format(System.currentTimeMillis())

        binding.flipBtn.setOnClickListener {
            if (videoRecorder != null) {
                Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        binding.captureBtn.setOnClickListener { onCapture() }

        binding.galleryBtn.setOnClickListener {
            setMode(Mode.EDIT)
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        binding.modeVideo.setOnClickListener { setMode(Mode.VIDEO) }
        binding.modeEdit.setOnClickListener {
            setMode(Mode.EDIT)
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                applyZoom(p / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        setMode(Mode.VIDEO)
        if (!hasPerms()) permLauncher.launch(REQUIRED_PERMS)
    }

    private fun setMode(m: Mode) {
        if (videoRecorder != null && m != Mode.VIDEO) {
            stopRecordingAndSave()
        }
        mode = m
        updateModeTabs()
        when (m) {
            Mode.PHOTO -> {
                binding.captureBtn.setBackgroundResource(R.drawable.photo_button)
                binding.photoPreview.visibility = View.GONE
                binding.glView.visibility = View.VISIBLE
                binding.modeTxt.text = "PHOTO"
            }
            Mode.VIDEO -> {
                binding.captureBtn.setBackgroundResource(R.drawable.rec_button)
                binding.photoPreview.visibility = View.GONE
                binding.glView.visibility = View.VISIBLE
                binding.modeTxt.text = "STBY"
            }
            Mode.EDIT -> {
                binding.captureBtn.setBackgroundResource(R.drawable.save_button)
                binding.modeTxt.text = "EDIT"
            }
        }
    }

    private fun updateModeTabs() {
        val active = ContextCompat.getColor(this, R.color.hud_accent)
        val dim = ContextCompat.getColor(this, R.color.hud_dim)
        listOf(
            binding.modePhoto to Mode.PHOTO,
            binding.modeVideo to Mode.VIDEO,
            binding.modeEdit to Mode.EDIT
        ).forEach { (tv: TextView, m) ->
            tv.setTextColor(if (mode == m) active else dim)
        }
    }

    private fun hasPerms(): Boolean = REQUIRED_PERMS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        if (!hasPerms()) return
        val st = pendingSurfaceTexture ?: return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()

            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()

            val rotDeg = computeRotation()
            val sensorRotated = rotDeg == 90f || rotDeg == 270f

            preview.setSurfaceProvider { req: SurfaceRequest ->
                val res = req.resolution
                st.setDefaultBufferSize(res.width, res.height)
                // After our shader rotation, displayed width/height may swap
                renderer.contentAspect = if (sensorRotated) {
                    res.height.toFloat() / res.width.toFloat()
                } else {
                    res.width.toFloat() / res.height.toFloat()
                }
                renderer.rotationDeg = rotDeg
                renderer.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
                val surface = Surface(st)
                req.provideSurface(surface, executor) { surface.release() }
            }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                camera = provider.bindToLifecycle(this, selector, preview)
                updateZoomLabel(1f)
                binding.zoomSeek.progress = 0
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun computeRotation(): Float {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK
        val sensorOrient = try {
            mgr.cameraIdList.firstNotNullOfOrNull { id ->
                val ch = mgr.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) == targetFacing)
                    ch.get(CameraCharacteristics.SENSOR_ORIENTATION)
                else null
            } ?: 90
        } catch (_: Throwable) { 90 }

        val displayRot = when (
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                display?.rotation ?: Surface.ROTATION_0
            else
                @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        ) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        // Standard Camera2 orientation formula
        val rot = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            (sensorOrient + displayRot) % 360
        } else {
            (sensorOrient - displayRot + 360) % 360
        }
        return rot.toFloat()
    }

    private fun applyZoom(linear: Float) {
        val cam = camera ?: return
        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        val ratio = 1f + (maxZoom - 1f) * linear
        cam.cameraControl.setZoomRatio(ratio)
        updateZoomLabel(ratio)
    }

    private fun updateZoomLabel(ratio: Float) {
        val bars = 8
        val filled = ((ratio - 1f) / 7f * bars).toInt().coerceIn(0, bars)
        val track = "W " + "─".repeat(filled) + "•" + "─".repeat(bars - filled) + " T"
        binding.zoomTxt.text = String.format(Locale.US, "%s  %.1fx", track, ratio)
    }

    private fun onCapture() {
        when (mode) {
            Mode.VIDEO -> toggleRecording()
            Mode.PHOTO -> capturePhoto()
            Mode.EDIT -> {
                Toast.makeText(this, "Pick an image via the gallery icon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun capturePhoto() {
        binding.captureBtn.isEnabled = false
        sound.play(MediaActionSound.SHUTTER_CLICK)
        renderer.requestFrameSnapshot { bmp ->
            if (bmp == null) {
                runOnUiThread {
                    Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show()
                    binding.captureBtn.isEnabled = true
                }
                return@requestFrameSnapshot
            }
            ioScope.launch {
                val savedUri = saveImage(bmp)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (savedUri != null) "Saved to Pictures/CCDCam" else "Save failed",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.captureBtn.isEnabled = true
                }
            }
        }
    }

    private fun saveImage(bmp: Bitmap): android.net.Uri? {
        val name = "ccdcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CCDCam")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            ?: return null
        contentResolver.openOutputStream(uri)?.use { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
        } ?: return null
        return uri
    }

    private fun toggleRecording() {
        if (videoRecorder != null) {
            sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            stopRecordingAndSave()
            return
        }
        // dimensions: keep aspect of contentAspect, target portrait at ~720p short edge
        val targetShort = 720
        val targetLong = (targetShort / renderer.contentAspect).toInt().let { l ->
            // round to even (H.264 requires even dims)
            if (l % 2 == 0) l else l + 1
        }
        val w = targetShort
        val h = targetLong
        val outFile = java.io.File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val rec = VideoRecorder(this, w, h)
        try {
            rec.start(outFile)
        } catch (t: Throwable) {
            Toast.makeText(this, "Recorder start failed: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }
        videoRecorder = rec
        recordingFile = outFile
        renderer.setEncoderSurface(rec.inputSurface, w, h)
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recordStartMs = SystemClock.elapsedRealtime()
        binding.modeTxt.text = "REC"
        binding.captureBtn.setBackgroundResource(R.drawable.rec_button_active)
        uiHandler.post(tickRunnable)
    }

    private fun stopRecordingAndSave() {
        val rec = videoRecorder ?: return
        val file = recordingFile
        videoRecorder = null
        recordingFile = null
        renderer.setEncoderSurface(null, 0, 0)
        binding.captureBtn.setBackgroundResource(R.drawable.rec_button)
        binding.modeTxt.text = "STBY"
        binding.recDot.visibility = View.INVISIBLE
        uiHandler.removeCallbacks(tickRunnable)

        ioScope.launch {
            try {
                rec.stop()
                val savedUri = if (file != null && file.exists() && file.length() > 0)
                    importVideoToGallery(file) else null
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (savedUri != null) "Saved to Movies/CCDCam" else "Save failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                file?.delete()
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save error: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importVideoToGallery(file: java.io.File): android.net.Uri? {
        val name = "ccdcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CCDCam")
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: return null
        contentResolver.openOutputStream(uri)?.use { os ->
            file.inputStream().use { it.copyTo(os) }
        } ?: return null
        return uri
    }

    private fun formatTimecode(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    override fun onResume() {
        super.onResume()
        binding.glView.onResume()
        binding.dateTxt.text = SimpleDateFormat("yyyy MM dd", Locale.US)
            .format(System.currentTimeMillis())
    }

    override fun onPause() {
        if (videoRecorder != null) stopRecordingAndSave()
        uiHandler.removeCallbacks(tickRunnable)
        binding.glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    companion object {
        private val REQUIRED_PERMS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
