package at.sturq.ccdcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: CcdRenderer
    private val executor = Executors.newSingleThreadExecutor()

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null

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

        binding.glView.setEGLContextClientVersion(2)
        renderer = CcdRenderer(this) { st ->
            pendingSurfaceTexture = st
            runOnUiThread { startCamera() }
        }
        binding.glView.setRenderer(renderer)
        binding.glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        binding.flipBtn.setOnClickListener {
            if (activeRecording != null) {
                Toast.makeText(this, "Stop recording before flipping", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        binding.recordBtn.setOnClickListener { toggleRecording() }

        if (!hasPerms()) permLauncher.launch(REQUIRED_PERMS)
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
                provider.bindToLifecycle(this, selector, preview, videoCapture!!)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        val current = activeRecording
        if (current != null) {
            current.stop()
            activeRecording = null
            binding.recordBtn.text = getString(R.string.record)
            return
        }
        val vc = videoCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "ccdcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CCDCam")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pending = vc.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        }
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.recordBtn.text = getString(R.string.stop)
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        Toast.makeText(
                            this, "Record error: ${event.error}", Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this, "Saved to Movies/CCDCam", Toast.LENGTH_SHORT
                        ).show()
                    }
                    binding.recordBtn.text = getString(R.string.record)
                }
            }
        }
    }

    override fun onResume() { super.onResume(); binding.glView.onResume() }
    override fun onPause() {
        activeRecording?.stop()
        activeRecording = null
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
