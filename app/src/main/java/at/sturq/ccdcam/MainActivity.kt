package at.sturq.ccdcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.content.Context
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: CcdRenderer
    private val executor = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null
    private var aspectRatio: Int = AspectRatio.RATIO_16_9
    private lateinit var prefs: SharedPreferences

    /**
     * Physical phone orientation. Mapping follows GrapheneOS Camera: the angle stored is
     * the rotation needed to put the captured image upright relative to how the phone is
     * held (Surface.ROTATION_* convention, but as raw degrees).
     */
    @Volatile private var physicalRotation: Int = 0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // GOS Camera mapping: phone tilted CW (top points right, orientation~90)
                // -> Surface.ROTATION_270 == 270°
                physicalRotation = when {
                    orientation < 45 -> 0
                    orientation < 135 -> 270
                    orientation < 225 -> 180
                    orientation < 315 -> 90
                    else -> 0
                }
            }
        }
    }

    private var mode = Mode.VIDEO
    private var videoRecorder: VideoRecorder? = null
    private var recordingFile: File? = null
    private var recordStartMs = 0L
    private var blinkOn = false

    private lateinit var scaleDetector: ScaleGestureDetector

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

    private val dateRunnable = object : Runnable {
        override fun run() {
            binding.dateTxt.text = SimpleDateFormat("yyyy MM dd", Locale.US)
                .format(System.currentTimeMillis())
            uiHandler.postDelayed(this, 60_000)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startCamera()
        else Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("ccdcam", Context.MODE_PRIVATE)
        aspectRatio = if (prefs.getString("aspect", "16:9") == "4:3")
            AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        binding.aspectBtn.setOnClickListener { toggleAspect() }

        binding.glView.setEGLContextClientVersion(2)
        renderer = CcdRenderer(this) { st ->
            pendingSurfaceTexture = st
            runOnUiThread { startCamera() }
        }
        binding.glView.setRenderer(renderer)
        binding.glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        // renderer is now ready, safe to push initial stretch into it
        applyAspectToLayout()

        // pinch-to-zoom
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                val newRatio = (zs.zoomRatio * detector.scaleFactor)
                    .coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(newRatio)
                updateZoomLabel(newRatio)
                return true
            }
        })
        binding.glView.setOnTouchListener { _, ev: MotionEvent ->
            scaleDetector.onTouchEvent(ev)
            true
        }

        binding.flipBtn.setOnClickListener {
            if (videoRecorder != null) {
                Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        binding.shutterBtn.setOnClickListener {
            when (mode) {
                Mode.PHOTO -> capturePhoto()
                Mode.VIDEO -> toggleRecording()
            }
        }

        binding.modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        binding.modeVideo.setOnClickListener { setMode(Mode.VIDEO) }

        setMode(Mode.VIDEO)
        if (!hasPerms()) permLauncher.launch(REQUIRED_PERMS)
    }

    private fun setMode(m: Mode) {
        if (videoRecorder != null && m != Mode.VIDEO) {
            stopRecording()
        }
        mode = m
        val active = ContextCompat.getColor(this, R.color.hud_accent)
        val dim = ContextCompat.getColor(this, R.color.hud_dim)
        binding.modePhoto.setTextColor(if (m == Mode.PHOTO) active else dim)
        binding.modeVideo.setTextColor(if (m == Mode.VIDEO) active else dim)
        binding.shutterBtn.setBackgroundResource(
            if (m == Mode.PHOTO) R.drawable.shutter_photo else R.drawable.shutter_video
        )
        binding.modeTxt.text = if (m == Mode.PHOTO) "PHOTO" else "STBY"
        binding.timecode.text = if (m == Mode.PHOTO) "          " else "00:00:00:00"
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
                .setTargetAspectRatio(aspectRatio)
                .build()

            preview.setSurfaceProvider { req: SurfaceRequest ->
                val res = req.resolution
                android.util.Log.i(
                    "CCDCam",
                    "Preview source resolution: ${res.width}x${res.height} " +
                        "(aspect ${"%.3f".format(res.width.toFloat() / res.height)})"
                )
                st.setDefaultBufferSize(res.width, res.height)
                val surface = Surface(st)
                req.provideSurface(surface, executor) { surface.release() }
            }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                camera = provider.bindToLifecycle(this, selector, preview)
                updateZoomLabel(camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleAspect() {
        if (videoRecorder != null) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        aspectRatio = if (aspectRatio == AspectRatio.RATIO_16_9)
            AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        prefs.edit().putString(
            "aspect",
            if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"
        ).apply()
        applyAspectToLayout()
        startCamera()
    }

    /** Push aspect-specific stretch factor into the shader and update the label. */
    private fun applyAspectToLayout() {
        // 16:9 output is taller (1792 px) than 4:3 (1344 px) for the same width, so the same
        // shader STRETCH value spreads the sampled source over more output pixels in 16:9 and
        // visually feels more stretched. compensate by scaling 4:3's stretch by the output
        // height ratio so both photos end up with equivalent per-pixel vertical zoom.
        renderer.stretch = if (aspectRatio == AspectRatio.RATIO_4_3) 0.72f * 3f / 4f else 0.72f
        binding.aspectBtn.text = if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"
    }

    private fun updateZoomLabel(ratio: Float) {
        binding.zoomTxt.text = String.format(Locale.US, "%.1f×", ratio)
        // dim zoom when at 1x, accent when zoomed in
        val color = if (ratio > 1.05f) R.color.hud_accent else R.color.hud_dim
        binding.zoomTxt.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun capturePhoto() {
        binding.shutterBtn.isEnabled = false
        renderer.requestFrameSnapshot { bmp ->
            if (bmp == null) {
                runOnUiThread {
                    Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show()
                    binding.shutterBtn.isEnabled = true
                }
                return@requestFrameSnapshot
            }
            // sample physical orientation at shutter time so it doesn't shift mid-process
            val rotDeg = physicalRotation
            ioScope.launch {
                // Stretch the captured framebuffer into the chosen aspect (portrait orientation):
                // 16:9 -> width × 16/9 tall, 4:3 -> width × 4/3 tall. Same visual content, but
                // saved file dimensions differ — toggle becomes visible in the output.
                val w = bmp.width
                val h = if (aspectRatio == AspectRatio.RATIO_4_3) w * 4 / 3 else w * 16 / 9
                val stretched = if (h != bmp.height)
                    android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                else bmp
                // GOS rotation needs +90° offset because our shader's texMatrix already
                // applied a 90° rotation to put portrait content upright in the framebuffer.
                val applyRot = (rotDeg + 90) % 360
                val finalBmp = if (applyRot == 0) stretched else {
                    val m = android.graphics.Matrix().apply { postRotate(applyRot.toFloat()) }
                    android.graphics.Bitmap.createBitmap(
                        stretched, 0, 0, stretched.width, stretched.height, m, true
                    )
                }
                val uri = savePhoto(finalBmp)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        if (uri != null) "Saved to Pictures/CCDCam" else "Save failed",
                        Toast.LENGTH_SHORT
                    ).show()
                    binding.shutterBtn.isEnabled = true
                }
            }
        }
    }

    private fun savePhoto(bmp: Bitmap): android.net.Uri? {
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
            stopRecording()
            return
        }
        binding.modeTxt.text = "REC"
        // encoder dimensions follow chosen aspect: 16:9 -> w*16/9 tall, 4:3 -> w*4/3 tall.
        val srcW = binding.glView.width.coerceAtLeast(2)
        val w = srcW and 1.inv()  // round down to even (H.264 requires even)
        val rawH = if (aspectRatio == AspectRatio.RATIO_4_3) w * 4 / 3 else w * 16 / 9
        val h = rawH and 1.inv()
        if (w < 16 || h < 16) {
            Toast.makeText(this, "Preview not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val outFile = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val rec = VideoRecorder(this, w, h)
        // same +90° offset as photo: shader-baked rotation already adds 90° to the frame.
        val rotHint = (physicalRotation + 90) % 360
        try {
            rec.start(outFile, rotHint)
        } catch (t: Throwable) {
            Toast.makeText(this, "Recorder start failed: ${t.message}", Toast.LENGTH_LONG).show()
            return
        }
        videoRecorder = rec
        recordingFile = outFile
        renderer.setEncoderSurface(rec.inputSurface, w, h, rec.startNs)
        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video_recording)
        recordStartMs = SystemClock.elapsedRealtime()
        uiHandler.post(tickRunnable)
    }

    private fun stopRecording() {
        val rec = videoRecorder ?: return
        val file = recordingFile
        videoRecorder = null
        recordingFile = null
        renderer.setEncoderSurface(null, 0, 0)
        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video)
        binding.recDot.visibility = View.INVISIBLE
        binding.modeTxt.text = "STBY"
        binding.timecode.text = "00:00:00:00"
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

    private fun importVideoToGallery(file: File): android.net.Uri? {
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
        val totalSec = ms / 1000
        val frames = ((ms % 1000) * 30 / 1000).toInt()  // simulated 30fps frame counter
        return String.format(
            Locale.US, "%02d:%02d:%02d:%02d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60, frames
        )
    }

    override fun onResume() {
        super.onResume()
        binding.glView.onResume()
        uiHandler.post(dateRunnable)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        if (videoRecorder != null) stopRecording()
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.removeCallbacks(dateRunnable)
        orientationListener.disable()
        binding.glView.onPause()
        super.onPause()
    }

    companion object {
        private val REQUIRED_PERMS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
