package com.omt.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.content.SharedPreferences
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    data class ResOption(val label: String, val size: Size)

    companion object {
        private const val TAG = "MainActivity"
        /** OMT port range per protocol (6400-6600). */
        private const val PORT_MIN = 6400
        private const val PORT_MAX = 6600
        private const val PREFS_NAME = "omt_camera_prefs"
        private const val KEY_STREAM_NAME = "stream_name"
        private const val DEFAULT_STREAM_NAME = "Android (OMT Camera)"
        private const val OVERLAY_AUTO_HIDE_MS = 5000L

        private val RESOLUTION_OPTIONS = listOf(
            ResOption("1080p", Size(1920, 1080)),
            ResOption("720p", Size(1280, 720)),
            ResOption("540p", Size(960, 540))
        )
        private const val DEFAULT_RES_INDEX = 0 // 1080p (matches GitHub alpha6 performance)

        private val FPS_OPTIONS = listOf(24, 25, 30, 50, 60)
        private const val DEFAULT_FPS_INDEX = 2 // 30 (matches GitHub; 50fps was causing stutter)
    }

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var safeGuideView: SafeGuideView
    private lateinit var statusBadge: TextView
    private lateinit var liveBadge: TextView
    private lateinit var overlayPanel: LinearLayout
    private lateinit var deviceIpText: TextView
    private lateinit var statusText: TextView
    private lateinit var videoHintText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var cameraSwitchButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var guidesButton: ImageButton
    private lateinit var badgeToggleButton: ImageButton
    private lateinit var refreshButton: ImageButton

    private lateinit var prefs: SharedPreferences
    private val portRandom = Random()
    private var useFrontCamera = false
    private var micEnabled = true
    private var streamSender: CameraStreamSender? = null
    private var discoveryRegistration: OmtDiscoveryRegistration? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzing = false
    private var selectedResolution: Size = RESOLUTION_OPTIONS[DEFAULT_RES_INDEX].size
    private var selectedFps: Int = FPS_OPTIONS[DEFAULT_FPS_INDEX]

    private val handler = Handler(Looper.getMainLooper())
    private var overlayVisible = true
    private var guidesEnabled = false
    private var badgeVisible = true
    private var isLive = false

    private val hideOverlayRunnable = Runnable { setOverlayVisible(false) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Permission result: camera=$cameraGranted audio=$audioGranted")
        if (!cameraGranted) {
            Toast.makeText(this, getString(R.string.error_permission), Toast.LENGTH_LONG).show()
        } else {
            startCamera()
            if (!audioGranted && results[Manifest.permission.RECORD_AUDIO] == false) {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, getString(R.string.permission_mic_denied), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        safeGuideView = findViewById(R.id.safeGuideView)
        statusBadge = findViewById(R.id.statusBadge)
        liveBadge = findViewById(R.id.liveBadge)
        overlayPanel = findViewById(R.id.overlayPanel)
        deviceIpText = findViewById(R.id.deviceIpText)
        statusText = findViewById(R.id.statusText)
        videoHintText = findViewById(R.id.videoHintText)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        cameraSwitchButton = findViewById(R.id.cameraSwitchButton)
        micButton = findViewById(R.id.micButton)
        guidesButton = findViewById(R.id.guidesButton)
        badgeToggleButton = findViewById(R.id.badgeToggleButton)
        refreshButton = findViewById(R.id.refreshButton)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        previewView.setOnClickListener { toggleOverlay() }
        safeGuideView.setOnClickListener { toggleOverlay() }

        cameraSwitchButton.setOnClickListener {
            useFrontCamera = !useFrontCamera
            startCamera()
            scheduleOverlayHide()
        }
        micButton.setOnClickListener {
            micEnabled = !micEnabled
            streamSender?.setAudioEnabled(micEnabled)
            updateMicIcon()
            scheduleOverlayHide()
        }
        refreshButton.setOnClickListener { restartStream(); scheduleOverlayHide() }

        // Grid guides toggle — white when on, gray when off
        updateGridIcon()
        guidesButton.setOnClickListener {
            guidesEnabled = !guidesEnabled
            safeGuideView.showGuides = guidesEnabled
            safeGuideView.visibility = if (guidesEnabled) View.VISIBLE else View.GONE
            updateGridIcon()
            scheduleOverlayHide()
        }

        // Badge visibility toggle
        badgeToggleButton.setOnClickListener {
            badgeVisible = !badgeVisible
            statusBadge.visibility = if (badgeVisible) View.VISIBLE else View.GONE
            liveBadge.visibility = if (badgeVisible && isLive) View.VISIBLE else View.GONE
            badgeToggleButton.alpha = if (badgeVisible) 0.5f else 1.0f
            scheduleOverlayHide()
        }
        badgeToggleButton.alpha = 0.5f

        updateMicIcon()
        setupResolutionSpinner()
        setupFpsSpinner()
        scheduleOverlayHide()
    }

    private fun updateMicIcon() {
        micButton.setImageResource(if (micEnabled) R.drawable.ic_mic else R.drawable.ic_mic_off)
    }

    private fun pickRandomPort(): Int = PORT_MIN + portRandom.nextInt(PORT_MAX - PORT_MIN + 1)

    private fun getStreamName(): String {
        val name = prefs.getString(KEY_STREAM_NAME, DEFAULT_STREAM_NAME)?.trim() ?: ""
        return if (name.isBlank()) DEFAULT_STREAM_NAME else name
    }

    private fun updateGridIcon() {
        if (guidesEnabled) {
            guidesButton.clearColorFilter()
        } else {
            val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
            guidesButton.colorFilter = ColorMatrixColorFilter(grayMatrix)
            guidesButton.alpha = 0.4f
        }
        if (guidesEnabled) guidesButton.alpha = 1.0f
    }

    private fun setupResolutionSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            RESOLUTION_OPTIONS.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter
        resolutionSpinner.setSelection(DEFAULT_RES_INDEX)
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newRes = RESOLUTION_OPTIONS[pos].size
                if (newRes != selectedResolution) {
                    selectedResolution = newRes
                    Log.i(TAG, "Resolution changed to ${newRes.width}x${newRes.height}")
                    restartStream(); startCamera()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFpsSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            FPS_OPTIONS.map { "${it}fps" })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = adapter
        fpsSpinner.setSelection(DEFAULT_FPS_INDEX)
        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newFps = FPS_OPTIONS[pos]
                if (newFps != selectedFps) {
                    selectedFps = newFps
                    Log.i(TAG, "FPS changed to $newFps")
                    restartStream(); startCamera()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---- Overlay auto-hide ----

    private fun toggleOverlay() {
        setOverlayVisible(!overlayVisible)
        if (overlayVisible) scheduleOverlayHide()
    }

    private fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        overlayPanel.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(250)
            .withStartAction { if (visible) overlayPanel.visibility = View.VISIBLE }
            .withEndAction { if (!visible) overlayPanel.visibility = View.GONE }
            .start()
    }

    private fun scheduleOverlayHide() {
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_AUTO_HIDE_MS)
    }

    // ---- Camera ----

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopStreaming()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun startCamera() {
        val fps = selectedFps
        val fpsRange = Range(fps, fps)

        val provider = ProcessCameraProvider.getInstance(this)
        provider.addListener({
            val cameraProvider = provider.get()

            val previewResSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            val previewBuilder = Preview.Builder().setResolutionSelector(previewResSelector)
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysisResSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(selectedResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            val imageAnalysis = analysisBuilder.build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        if (analyzing) streamSender?.sendFrame(image)
                        image.close()
                    }
                }
            try {
                val cameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.i(TAG, "Camera bound: ${if (useFrontCamera) "front" else "back"}, analysis=${imageAnalysis.resolutionInfo?.resolution}, fps=$fps")
                if (streamSender == null) startStreaming()
            } catch (e: Exception) {
                Log.e(TAG, "Bind failed", e)
                Toast.makeText(this, getString(R.string.error_camera), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Streaming ----

    private fun restartStream() { stopStreaming(); startStreaming() }

    private fun startStreaming() {
        val port = pickRandomPort()
        analyzing = true
        val sourceName = getStreamName()
        Log.i(TAG, "Starting stream on port $port as \"$sourceName\"")
        discoveryRegistration = OmtDiscoveryRegistration(
            context = this, sourceName = sourceName,
            onRegistered = { name -> runOnUiThread { statusBadge.text = name; updateStreamStatus(port, null) } },
            onRegistrationFailed = { msg -> runOnUiThread {
                statusText.text = getString(R.string.not_streaming) + " — $msg"
                deviceIpText.text = getString(R.string.vmix_fallback_hint, getLocalIpAddress() ?: "?", port)
            }}
        )
        streamSender = CameraStreamSender(
            port = port,
            targetFps = selectedFps,
            context = this,
            onServerListening = {
                runOnUiThread {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    updateStreamStatus(port, null)
                    videoHintText.visibility = View.VISIBLE
                    videoHintText.text = if (VmxEncoder.isAvailable())
                        getString(R.string.video_vmx_active) else getString(R.string.video_vmx_required)
                    discoveryRegistration?.register(port, getLocalIpAddress())
                }
            },
            onClientConnected = { clientIp -> runOnUiThread {
                statusText.text = getString(R.string.streaming_to, clientIp, port)
                statusBadge.text = "STREAMING $clientIp"
                setLive(true)
            }},
            onClientDisconnected = { runOnUiThread {
                statusText.text = getString(R.string.not_streaming) + " (no client)"
                statusBadge.text = getString(R.string.not_streaming)
                setLive(false)
            }},
            onError = { e ->
                Log.w(TAG, "Stream error", e)
                val isPortInUse = e is BindException ||
                    (e.message?.lowercase()?.contains("address already in use") == true)
                runOnUiThread {
                    if (isPortInUse) {
                        streamSender = null; discoveryRegistration?.unregister(); discoveryRegistration = null
                        analyzing = false; statusText.text = getString(R.string.not_streaming)
                        videoHintText.visibility = View.GONE
                        Toast.makeText(this, getString(R.string.port_in_use_hint), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        streamSender?.setAudioEnabled(micEnabled)
        streamSender?.start()
        startStreamingService(port)
        updateStreamStatus(port, null)
    }

    private fun setLive(live: Boolean) {
        isLive = live
        liveBadge.visibility = if (live && badgeVisible) View.VISIBLE else View.GONE
    }

    private fun startStreamingService(port: Int) {
        val intent = Intent(this, StreamingService::class.java).apply { putExtra("port", port) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopStreamingService() { stopService(Intent(this, StreamingService::class.java)) }

    private fun updateStreamStatus(port: Int, clientIp: String?) {
        val ip = getLocalIpAddress()
        deviceIpText.text = when {
            clientIp != null -> getString(R.string.streaming_to, clientIp, port)
            ip != null -> getString(R.string.connect_at, ip, port)
            else -> getString(R.string.vmix_discovery_hint)
        }
        statusText.text = when {
            clientIp != null -> getString(R.string.streaming_to, clientIp, port)
            else -> getString(R.string.not_streaming) + " — waiting for vMix…"
        }
        statusBadge.text = when {
            clientIp != null -> "LIVE $clientIp"
            ip != null -> "$ip:$port"
            else -> getString(R.string.not_streaming)
        }
    }

    private fun stopStreaming() {
        analyzing = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopStreamingService()
        discoveryRegistration?.unregister(); discoveryRegistration = null
        streamSender?.stop(); streamSender = null
        setLive(false)
        statusText.text = getString(R.string.not_streaming)
        statusBadge.text = getString(R.string.not_streaming)
        deviceIpText.text = getString(R.string.vmix_discovery_hint)
        videoHintText.visibility = View.GONE
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val list = Collections.list(ifaces)
            val wlanFirst = list.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            for (iface in wlanFirst) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in Collections.list<InetAddress>(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    override fun onResume() {
        super.onResume()
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "onResume: camera=$cameraGranted audio=$audioGranted")

        val permsToRequest = mutableListOf<String>()
        if (!cameraGranted) permsToRequest.add(Manifest.permission.CAMERA)
        if (!audioGranted) permsToRequest.add(Manifest.permission.RECORD_AUDIO)

        if (permsToRequest.isEmpty()) {
            startCamera()
        } else {
            if (permsToRequest.contains(Manifest.permission.RECORD_AUDIO)) {
                AlertDialog.Builder(this)
                    .setTitle("Microphone")
                    .setMessage(getString(R.string.permission_mic_rationale))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        permissionLauncher.launch(permsToRequest.toTypedArray())
                    }
                    .show()
            } else {
                permissionLauncher.launch(permsToRequest.toTypedArray())
            }
        }
    }
}
