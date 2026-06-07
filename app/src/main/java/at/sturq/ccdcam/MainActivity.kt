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

    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: CcdRenderer
    private val executor = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var pendingSurfaceTexture: SurfaceTexture? = null

    // recording state
    private var videoRecorder: VideoRecorder? = null
    private var recordingFile: File? = null

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
            if (videoRecorder != null) {
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

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.bindToLifecycle(this, selector, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        if (videoRecorder != null) {
            stopRecording()
            return
        }
        // Encode at the same dimensions as the on-screen preview so the saved
        // video looks like what the user sees (CCD filter + portrait stretch).
        // H.264 requires even dimensions.
        val w = binding.glView.width.coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val h = binding.glView.height.coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        if (w < 16 || h < 16) {
            Toast.makeText(this, "Preview not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val outFile = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
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
        binding.recordBtn.text = getString(R.string.stop)
    }

    private fun stopRecording() {
        val rec = videoRecorder ?: return
        val file = recordingFile
        videoRecorder = null
        recordingFile = null
        renderer.setEncoderSurface(null, 0, 0)
        binding.recordBtn.text = getString(R.string.record)

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

    override fun onResume() { super.onResume(); binding.glView.onResume() }
    override fun onPause() {
        if (videoRecorder != null) stopRecording()
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
