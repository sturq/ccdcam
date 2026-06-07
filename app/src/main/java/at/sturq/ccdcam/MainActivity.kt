package at.sturq.ccdcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
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
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
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
            if (activeRecording != null) {
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
            if (activeRecording != null) {
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
        if (activeRecording != null && m != Mode.VIDEO) {
            activeRecording?.stop(); activeRecording = null
            uiHandler.removeCallbacks(tickRunnable)
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

            preview.setSurfaceProvider { req: SurfaceRequest ->
                val res = req.resolution
                st.setDefaultBufferSize(res.width, res.height)
                // After 90deg rotation, the content displayed is portrait (h/w aspect)
                renderer.contentAspect = res.height.toFloat() / res.width.toFloat()
                renderer.rotationDeg =
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) 270f else 90f
                renderer.mirror = lensFacing == CameraSelector.LENS_FACING_FRONT
                val surface = Surface(st)
                req.provideSurface(surface, executor) { surface.release() }
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder).also {
                it.targetRotation = Surface.ROTATION_0
            }

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                camera = provider.bindToLifecycle(this, selector, preview, videoCapture!!)
                updateZoomLabel(1f)
                binding.zoomSeek.progress = 0
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
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
        val current = activeRecording
        if (current != null) {
            sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            current.stop()
            activeRecording = null
            binding.captureBtn.setBackgroundResource(R.drawable.rec_button)
            binding.modeTxt.text = "STBY"
            binding.recDot.visibility = View.INVISIBLE
            uiHandler.removeCallbacks(tickRunnable)
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
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(cv).build()

        val pending = vc.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        }
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStartMs = SystemClock.elapsedRealtime()
                    binding.modeTxt.text = "REC"
                    binding.captureBtn.setBackgroundResource(R.drawable.rec_button_active)
                    uiHandler.post(tickRunnable)
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Toast.makeText(this, "Record error: ${event.error}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Saved to Movies/CCDCam", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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
        activeRecording?.stop()
        activeRecording = null
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
