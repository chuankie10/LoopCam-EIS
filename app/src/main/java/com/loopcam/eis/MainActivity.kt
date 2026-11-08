package com.loopcam.eis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.*
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val ui = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var videoSize = Size(1920, 1080)
    private var eisAvailable = false
    private var recording = false
    private var segment = 0
    private var currentFile: File? = null
    private var segmentTask: Runnable? = null
    private var elapsedTask: Runnable? = null
    private lateinit var outputDir: File
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "LoopCamEIS").apply { mkdirs() }
        buildUi()
        thread = HandlerThread("LoopCamCamera").also { it.start() }
        cameraHandler = Handler(thread!!.looper)
        if (hasPermissions()) openWhenReady() else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100
        )
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFF101820.toInt())
        }
        val title = TextView(this).apply {
            text = "LOOPCAM EIS  •  BETA"; textSize = 22f
            setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
        }
        preview = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        status = TextView(this).apply {
            text = "Menyiapkan kamera..."; textSize = 14f
            setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
            setPadding(4, 10, 4, 6)
        }
        timer = TextView(this).apply {
            text = "00:00:00"; textSize = 26f
            setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = Button(this).apply { text = "START"; isEnabled = false; setOnClickListener { startLoop() } }
        stopButton = Button(this).apply { text = "STOP"; isEnabled = false; setOnClickListener { stopLoop() } }
        controls.addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(title); root.addView(preview); root.addView(status); root.addView(timer); root.addView(controls)
        setContentView(root)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openWhenReady() { if (preview.isAvailable) openCamera() }

    private fun openCamera() {
        if (!hasPermissions() || camera != null) return
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: throw IllegalStateException("Kamera belakang tidak ditemukan")
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException("Konfigurasi kamera tidak tersedia")
            val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
            videoSize = sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: sizes.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width * it.height }
                ?: throw IllegalStateException("Resolusi video tidak ditemukan")
            eisAvailable = (chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf())
                .contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(value: CameraDevice) {
                    camera = value
                    status.text = "Kamera siap • ${videoSize.width}×${videoSize.height} • EIS ${if (eisAvailable) "tersedia" else "tidak tersedia"}"
                    startButton.isEnabled = true
                    createPreviewSession()
                }
                override fun onDisconnected(value: CameraDevice) { value.close(); camera = null; status.text = "Kamera terputus" }
                override fun onError(value: CameraDevice, error: Int) { value.close(); camera = null; status.text = "Kesalahan kamera: $error" }
            }, cameraHandler)
        } catch (e: Exception) { status.text = "Gagal membuka kamera: ${e.message}" }
    }

    private fun createPreviewSession() {
        val cam = camera ?: return
        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(videoSize.width, videoSize.height)
        val surface = Surface(texture)
        try {
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    session = value
                    try {
                        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (eisAvailable) 1 else 0)
                        }.build()
                        value.setRepeatingRequest(request, null, cameraHandler)
                    } catch (e: Exception) { status.text = "Preview gagal: ${e.message}" }
                }
                override fun onConfigureFailed(value: CameraCaptureSession) { status.text = "Konfigurasi preview gagal" }
            }, cameraHandler)
        } catch (e: Exception) { status.text = "Preview gagal: ${e.message}" }
    }

    private fun startLoop() {
        if (recording || camera == null) return
        recording = true; segment = 0; startedAt = System.currentTimeMillis()
        startButton.isEnabled = false; stopButton.isEnabled = true
        startSegment()
        elapsedTask = object : Runnable {
            override fun run() {
                if (!recording) return
                val sec = (System.currentTimeMillis() - startedAt) / 1000
                timer.text = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
                ui.postDelayed(this, 500)
            }
        }.also { ui.post(it) }
    }

    private fun startSegment() {
        if (!recording) return
        val cam = camera ?: return
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(outputDir, "LOOPCAM_${stamp}_${String.format(Locale.US, "%04d", ++segment)}.mp4")
            currentFile = file
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder = mr
            mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(file.absolutePath)
            mr.setVideoEncodingBitRate(8_000_000)
            mr.setVideoFrameRate(30)
            mr.setVideoSize(videoSize.width, videoSize.height)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128_000)
            mr.setAudioSamplingRate(44100)
            mr.prepare()
            if (NoiseSuppressor.isAvailable()) {
                try { noiseSuppressor = NoiseSuppressor.create(mr.audioSessionId)?.apply { enabled = true } }
                catch (_: Exception) { noiseSuppressor = null }
            }
            val videoSurface = mr.surface
            val texture = preview.surfaceTexture ?: throw IllegalStateException("Preview tidak tersedia")
            texture.setDefaultBufferSize(videoSize.width, videoSize.height)
            val previewSurface = Surface(texture)
            cam.createCaptureSession(listOf(previewSurface, videoSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    if (!recording) { value.close(); return }
                    session?.close(); session = value
                    try {
                        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(previewSurface); addTarget(videoSurface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (eisAvailable) 1 else 0)
                        }.build()
                        value.setRepeatingRequest(request, null, cameraHandler)
                        mr.start()
                        status.text = "MEREKAM • Segmen $segment • EIS ${if (eisAvailable) "diminta" else "tidak didukung"} • Noise suppressor ${if (noiseSuppressor != null) "aktif" else "tidak tersedia"}"
                        segmentTask = Runnable { finishSegment() }.also { ui.postDelayed(it, 10_000) }
                    } catch (e: Exception) { status.text = "Gagal mulai: ${e.message}"; stopLoop() }
                }
                override fun onConfigureFailed(value: CameraCaptureSession) { status.text = "Konfigurasi rekaman gagal"; stopLoop() }
            }, cameraHandler)
        } catch (e: Exception) { status.text = "Gagal menyiapkan segmen: ${e.message}"; stopLoop() }
    }

    private fun finishSegment() {
        if (!recording) return
        try { session?.stopRepeating(); recorder?.stop() } catch (_: Exception) { currentFile?.delete() }
        releaseRecorder()
        session?.close(); session = null
        if (recording) startSegment()
    }

    private fun stopLoop() {
        if (!recording && recorder == null) return
        recording = false
        segmentTask?.let { ui.removeCallbacks(it) }
        elapsedTask?.let { ui.removeCallbacks(it) }
        try { session?.stopRepeating(); recorder?.stop() } catch (_: Exception) { currentFile?.delete() }
        releaseRecorder()
        session?.close(); session = null
        startButton.isEnabled = camera != null; stopButton.isEnabled = false
        status.text = "STOP • $segment segmen selesai"
        createPreviewSession()
    }

    private fun releaseRecorder() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) { if (hasPermissions()) openWhenReady() else status.text = "Izin kamera dan mikrofon diperlukan." }
    }

    override fun onPause() { if (recording) stopLoop(); super.onPause() }
    override fun onDestroy() {
        try { session?.close(); camera?.close() } catch (_: Exception) {}
        camera = null; thread?.quitSafely(); thread = null
        super.onDestroy()
    }
}