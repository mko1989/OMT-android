package com.omt.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton

class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ViewerActivity"
        private const val OVERLAY_AUTO_HIDE_MS = 6000L
    }

    private lateinit var videoSurface: SurfaceView
    private lateinit var statusBadge: TextView
    private lateinit var overlayPanel: LinearLayout
    private lateinit var sourceSpinner: Spinner
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var manualHostEdit: EditText
    private lateinit var manualPortEdit: EditText
    private lateinit var manualConnectButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var badgeToggleButton: ImageButton

    private var sourceBrowser: OmtSourceBrowser? = null
    private var receiver: OmtStreamReceiver? = null
    private var surfaceReady = false
    private var badgeVisible = true

    private val discoveredSources = mutableListOf<OmtSourceBrowser.OmtSource>()
    private lateinit var sourceAdapter: ArrayAdapter<String>

    // Native decode now outputs RGBA directly (swap done in C), so no color filter needed.
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var overlayVisible = true
    private var isConnected = false

    private val hideOverlayRunnable = Runnable { setOverlayVisible(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_viewer)

        videoSurface = findViewById(R.id.videoSurface)
        statusBadge = findViewById(R.id.statusBadge)
        overlayPanel = findViewById(R.id.overlayPanel)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        manualHostEdit = findViewById(R.id.manualHostEdit)
        manualPortEdit = findViewById(R.id.manualPortEdit)
        manualConnectButton = findViewById(R.id.manualConnectButton)
        statusText = findViewById(R.id.statusText)
        badgeToggleButton = findViewById(R.id.badgeToggleButton)

        sourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>())
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceSpinner.adapter = sourceAdapter

        videoSurface.holder.setFormat(PixelFormat.RGBA_8888)
        videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }
        })

        videoSurface.setOnClickListener { toggleOverlay() }

        connectButton.setOnClickListener { connectToSelected(); scheduleOverlayHide() }
        disconnectButton.setOnClickListener { disconnect(); scheduleOverlayHide() }
        manualConnectButton.setOnClickListener { connectManual(); scheduleOverlayHide() }

        badgeToggleButton.setOnClickListener {
            badgeVisible = !badgeVisible
            statusBadge.visibility = if (badgeVisible) View.VISIBLE else View.GONE
            badgeToggleButton.alpha = if (badgeVisible) 0.5f else 1.0f
            scheduleOverlayHide()
        }
        badgeToggleButton.alpha = 0.5f

        startBrowsing()
        scheduleOverlayHide()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        disconnect()
        sourceBrowser?.stop()
        sourceBrowser = null
        super.onDestroy()
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

    // ---- Source discovery ----

    private fun startBrowsing() {
        sourceBrowser = OmtSourceBrowser(
            context = this,
            onSourceFound = { source ->
                runOnUiThread {
                    if (discoveredSources.none { it.host == source.host && it.port == source.port }) {
                        discoveredSources.add(source)
                        rebuildSourceList()
                        statusText.text = "${discoveredSources.size} source(s) found"
                    }
                }
            },
            onSourceLost = { name ->
                runOnUiThread {
                    discoveredSources.removeAll { it.name == name }
                    rebuildSourceList()
                    statusText.text = if (discoveredSources.isEmpty()) "Scanning…"
                    else "${discoveredSources.size} source(s)"
                }
            }
        )
        sourceBrowser?.start()
    }

    private fun rebuildSourceList() {
        sourceAdapter.clear()
        sourceAdapter.addAll(discoveredSources.map { it.toString() })
        sourceAdapter.notifyDataSetChanged()
    }

    // ---- Connection ----

    private fun connectToSelected() {
        val pos = sourceSpinner.selectedItemPosition
        if (pos < 0 || pos >= discoveredSources.size) {
            Toast.makeText(this, "No source selected", Toast.LENGTH_SHORT).show()
            return
        }
        val source = discoveredSources[pos]
        connectTo(source.host, source.port)
    }

    private fun connectManual() {
        val host = manualHostEdit.text.toString().trim()
        val portStr = manualPortEdit.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter a host address", Toast.LENGTH_SHORT).show()
            return
        }
        connectTo(host, portStr.toIntOrNull() ?: 6500)
    }

    private fun connectTo(host: String, port: Int) {
        disconnect()
        Log.i(TAG, "Connecting to $host:$port")
        setConnectedUI(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusBadge.text = "Connecting…"

        receiver = OmtStreamReceiver(
            host = host, port = port,
            onFrame = { bitmap -> renderFrame(bitmap) },
            onStatus = { msg -> runOnUiThread {
                if (badgeVisible) statusBadge.text = msg
                statusText.text = msg
            }},
            onError = { msg ->
                runOnUiThread {
                    statusBadge.text = "Disconnected"
                    statusText.text = msg
                    setConnectedUI(false)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setOverlayVisible(true); scheduleOverlayHide()
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
        receiver?.start()
    }

    private fun disconnect() {
        receiver?.stop()
        receiver = null
        runOnUiThread {
            setConnectedUI(false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            statusBadge.text = "Disconnected"
            statusText.text = if (discoveredSources.isEmpty()) "Scanning…"
            else "${discoveredSources.size} source(s)"
            if (surfaceReady) {
                try {
                    val canvas = videoSurface.holder.lockCanvas()
                    canvas?.let { it.drawColor(Color.BLACK); videoSurface.holder.unlockCanvasAndPost(it) }
                } catch (_: Exception) {}
            }
        }
    }

    private fun setConnectedUI(connected: Boolean) {
        isConnected = connected
        connectButton.isEnabled = !connected
        disconnectButton.isEnabled = connected
        manualConnectButton.isEnabled = !connected
        sourceSpinner.isEnabled = !connected
    }

    // ---- Video rendering ----

    private fun renderFrame(bitmap: Bitmap) {
        if (!surfaceReady) return
        val holder = videoSurface.holder
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas() ?: return
            val surfW = canvas.width.toFloat()
            val surfH = canvas.height.toFloat()
            val bmpW = bitmap.width.toFloat()
            val bmpH = bitmap.height.toFloat()

            val scale = minOf(surfW / bmpW, surfH / bmpH)
            val dstW = (bmpW * scale).toInt()
            val dstH = (bmpH * scale).toInt()
            val offsetX = ((surfW - dstW) / 2).toInt()
            val offsetY = ((surfH - dstH) / 2).toInt()

            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(offsetX, offsetY, offsetX + dstW, offsetY + dstH),
                paint)
        } catch (e: Exception) {
            Log.w(TAG, "renderFrame error: ${e.message}")
        } finally {
            if (canvas != null) {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }
    }
}
