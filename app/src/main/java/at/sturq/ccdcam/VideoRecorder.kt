package at.sturq.ccdcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Custom video pipeline that takes GL-rendered frames from CcdRenderer (with CCD filter and
 * proper portrait rotation already baked in) and encodes them to H.264 + AAC inside an MP4.
 *
 * The video Surface is exposed via [inputSurface] for the renderer to dual-render into.
 * Audio is captured via AudioRecord if RECORD_AUDIO is granted.
 *
 * After [stop] returns, the file at the provided path is finalized and can be inserted
 * into MediaStore.
 */
class VideoRecorder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
) {
    companion object {
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val VIDEO_BITRATE = 12_000_000
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_BUF_SIZE = 2048
        private const val TIMEOUT_US = 10_000L
    }

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIdx = -1
    private var audioTrackIdx = -1
    private var muxerStarted = false
    private val muxerLock = Object()
    private var audioThread: Thread? = null
    private var videoDrainThread: Thread? = null
    @Volatile private var recording = false
    private var startNs = 0L

    /** Provided to caller for GL rendering. Valid only between [start] and [stop]. */
    var inputSurface: Surface? = null
        private set

    /** Absolute file path written to. */
    var outputPath: String? = null
        private set

    fun start(outFile: File) {
        outputPath = outFile.absolutePath

        // ---- video encoder ----
        val vFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
            configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        // ---- audio encoder + recorder (best-effort: skip if permission denied) ----
        val hasAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudio) {
            val aFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                setInteger(MediaFormat.KEY_SAMPLE_RATE, AUDIO_SAMPLE_RATE)
            }
            audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(AUDIO_BUF_SIZE * 4)
            @Suppress("MissingPermission")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        }

        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        recording = true
        startNs = System.nanoTime()

        videoDrainThread = thread(name = "ccd-video-drain") { videoDrainLoop() }
        if (hasAudio) {
            audioRecord?.startRecording()
            audioThread = thread(name = "ccd-audio") { audioLoop() }
        }
    }

    fun stop() {
        if (!recording) return
        recording = false
        // wake audio loop and let it drain
        audioThread?.join(2000)
        // signal video EOS on encoder; drain thread will exit
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (_: Throwable) {}
        videoDrainThread?.join(3000)

        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        try { audioCodec?.stop() } catch (_: Throwable) {}
        try { audioCodec?.release() } catch (_: Throwable) {}
        try { videoCodec?.stop() } catch (_: Throwable) {}
        try { videoCodec?.release() } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}

        synchronized(muxerLock) {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Throwable) {}
            }
            try { muxer?.release() } catch (_: Throwable) {}
            muxer = null
            muxerStarted = false
        }

        inputSurface = null
        audioRecord = null
        videoCodec = null
        audioCodec = null
        audioThread = null
        videoDrainThread = null
    }

    private fun videoDrainLoop() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = try {
                codec.dequeueOutputBuffer(info, TIMEOUT_US)
            } catch (_: IllegalStateException) {
                break
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!recording && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                        // recording stopped but encoder hasn't yielded EOS yet; keep polling
                        continue
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (videoTrackIdx < 0) {
                            videoTrackIdx = muxer!!.addTrack(codec.outputFormat)
                            maybeStartMuxer()
                        }
                    }
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx) ?: continue
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted && videoTrackIdx >= 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                muxer!!.writeSampleData(videoTrackIdx, out, info)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                else -> {}
            }
        }
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val rec = audioRecord ?: return
        val buf = ByteArray(AUDIO_BUF_SIZE)
        val info = MediaCodec.BufferInfo()
        var sentEos = false
        while (true) {
            val active = recording
            if (active) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        ib.put(buf, 0, n)
                        val pts = (System.nanoTime() - startNs) / 1000
                        codec.queueInputBuffer(ii, 0, n, pts, 0)
                    }
                }
            } else if (!sentEos) {
                val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                if (ii >= 0) {
                    codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sentEos = true
                }
            }
            // drain
            while (true) {
                val oi = try {
                    codec.dequeueOutputBuffer(info, 0)
                } catch (_: IllegalStateException) { -1 }
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        if (audioTrackIdx < 0) {
                            audioTrackIdx = muxer!!.addTrack(codec.outputFormat)
                            maybeStartMuxer()
                        }
                    }
                    continue
                }
                if (oi < 0) break
                val out = codec.getOutputBuffer(oi) ?: continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0) {
                    synchronized(muxerLock) {
                        if (muxerStarted && audioTrackIdx >= 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            muxer!!.writeSampleData(audioTrackIdx, out, info)
                        }
                    }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
            if (sentEos && info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun maybeStartMuxer() {
        val hasAudio = audioCodec != null
        val ready = videoTrackIdx >= 0 && (!hasAudio || audioTrackIdx >= 0)
        if (ready && !muxerStarted) {
            muxer?.start()
            muxerStarted = true
        }
    }
}
