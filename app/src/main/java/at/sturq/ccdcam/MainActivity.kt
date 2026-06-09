package at.sturq.ccdcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import at.sturq.ccdcam.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * CCDCam: standard CameraX pipeline (PreviewView + VideoCapture<Recorder>) with the CCD
 * shader injected via [CcdEffect] (a SurfaceProcessor-based CameraEffect). All rotation
 * and orientation handling is delegated to CameraX — we never rotate bitmaps or compute
 * encoder dimensions manually. Photo is grabbed from PreviewView.bitmap, which is already
 * filtered and matches what the user sees.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private lateinit var ccdProcessor: CcdSurfaceProcessor
    private lateinit var ccdEffect: CcdEffect

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var aspectRatio: Int = AspectRatio.RATIO_16_9
    private var mode = Mode.VIDEO

    @Volatile private var lastSurfaceRotation: Int = Surface.ROTATION_0
    /** CW degrees to apply to PreviewView.bitmap before saving a photo (the bitmap is
     *  always in display orientation; we need to bake the physical rotation in). */
    @Volatile private var photoRotationCw: Int = 0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rot = when {
                    orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    orientation < 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                photoRotationCw = when {
                    orientation < 45 -> 0
                    orientation < 135 -> 90
                    orientation < 225 -> 180
                    orientation < 315 -> 270
                    else -> 0
                }
                if (rot != lastSurfaceRotation) {
                    lastSurfaceRotation = rot
                    videoCapture?.targetRotation = rot
                }
            }
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var recordStartMs = 0L
    private var blinkOn = false

    private lateinit var scaleDetector: ScaleGestureDetector

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (recording != null) {
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
        if (result.values.all { it }) bindCamera()
        else Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("ccdcam", Context.MODE_PRIVATE)
        aspectRatio = if (prefs.getString("aspect", "16:9") == "4:3")
            AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9

        ccdProcessor = CcdSurfaceProcessor(this)
        ccdEffect = CcdEffect(ccdProcessor)
        applyAspectLabel()

        binding.aspectBtn.setOnClickListener { toggleAspect() }
        binding.flipBtn.setOnClickListener {
            if (recording != null) {
                Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        binding.shutterBtn.setOnClickListener {
            when (mode) {
                Mode.PHOTO -> capturePhoto()
                Mode.VIDEO -> toggleRecording()
            }
        }
        binding.modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        binding.modeVideo.setOnClickListener { setMode(Mode.VIDEO) }

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
        binding.previewView.setOnTouchListener { _, ev: MotionEvent ->
            scaleDetector.onTouchEvent(ev)
            true
        }

        setMode(Mode.VIDEO)
        if (!hasPerms()) permLauncher.launch(REQUIRED_PERMS) else bindCamera()
    }

    private fun setMode(m: Mode) {
        if (recording != null && m != Mode.VIDEO) stopRecording()
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

    private fun hasPerms() = REQUIRED_PERMS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindCamera() {
        if (!hasPerms()) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            provider.unbindAll()

            preview = Preview.Builder()
                .setTargetAspectRatio(aspectRatio)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder).also {
                it.targetRotation = lastSurfaceRotation
            }

            val group = UseCaseGroup.Builder()
                .addUseCase(preview!!)
                .addUseCase(videoCapture!!)
                .addEffect(ccdEffect)
                .build()

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                camera = provider.bindToLifecycle(this, selector, group)
                updateZoomLabel(camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleAspect() {
        if (recording != null) {
            Toast.makeText(this, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        aspectRatio = if (aspectRatio == AspectRatio.RATIO_16_9)
            AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        prefs.edit().putString(
            "aspect",
            if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"
        ).apply()
        applyAspectLabel()
        bindCamera()
    }

    private fun applyAspectLabel() {
        // Horizontal anamorphic squish in the shader (uStretch on X axis) — applies to both
        // preview and video output uniformly, so videos get the CCD/Hi8 character that comes
        // from a tighter horizontal sample range scaled to fill output width.
        ccdProcessor.stretch = 0.85f
        binding.aspectBtn.text = if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"
    }

    private fun updateZoomLabel(ratio: Float) {
        binding.zoomTxt.text = String.format(Locale.US, "%.1f×", ratio)
        val color = if (ratio > 1.05f) R.color.hud_accent else R.color.hud_dim
        binding.zoomTxt.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun capturePhoto() {
        val bmp = binding.previewView.bitmap
        if (bmp == null) {
            Toast.makeText(this, "Preview not ready", Toast.LENGTH_SHORT).show()
            return
        }
        binding.shutterBtn.isEnabled = false
        val rot = photoRotationCw
        ioScope.launch {
            // 1. Rotate to physical orientation so floor ends up at the bottom of the file.
            val upright = if (rot == 0) bmp else {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            // No anamorphic post-process: reference OG sushitrash footage has no visible
            // stretch (buildings/trees keep normal proportions). The CCD aesthetic comes
            // from the shader's warm grade, lifted blacks, soft scanlines, and vignette —
            // all already applied during preview rendering.
            val uri = savePhoto(upright)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (uri != null) "Saved to Pictures/CCDCam" else "Save failed",
                    Toast.LENGTH_SHORT,
                ).show()
                binding.shutterBtn.isEnabled = true
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

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        recording?.let {
            stopRecording()
            return
        }
        val vc = videoCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "ccdcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CCDCam")
            }
        }
        val opts = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build()

        vc.targetRotation = lastSurfaceRotation
        binding.modeTxt.text = "REC"
        recording = vc.output
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video_recording)
                        recordStartMs = SystemClock.elapsedRealtime()
                        uiHandler.post(tickRunnable)
                    }
                    is VideoRecordEvent.Finalize -> {
                        binding.shutterBtn.setBackgroundResource(R.drawable.shutter_video)
                        binding.recDot.visibility = View.INVISIBLE
                        binding.modeTxt.text = "STBY"
                        binding.timecode.text = "00:00:00:00"
                        uiHandler.removeCallbacks(tickRunnable)
                        val msg = if (event.hasError())
                            "Save error: ${event.error}"
                        else
                            "Saved to Movies/CCDCam"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    private fun formatTimecode(ms: Long): String {
        val totalSec = ms / 1000
        val frames = ((ms % 1000) * 30 / 1000).toInt()
        return String.format(
            Locale.US, "%02d:%02d:%02d:%02d",
            totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60, frames
        )
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(dateRunnable)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        if (recording != null) stopRecording()
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.removeCallbacks(dateRunnable)
        orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ccdProcessor.release()
    }

    companion object {
        private val REQUIRED_PERMS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
