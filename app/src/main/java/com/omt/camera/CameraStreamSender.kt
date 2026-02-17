package com.omt.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Sends camera frames and microphone audio over TCP using the OMT wire protocol.
 *
 * Architecture: producer-consumer to decouple camera from encoding.
 *   Camera thread → snapshots Y+UV into a double-buffer → signals encode thread
 *   Encode thread → VMX encode → network write (high priority, zero-alloc)
 *   Audio thread → AudioRecord → PCM float → network write
 */
class CameraStreamSender(
    private val port: Int,
    private val targetFps: Int = 30,
    private val context: Context? = null,
    private val onServerListening: (() -> Unit)? = null,
    private val onClientConnected: ((String) -> Unit)? = null,
    private val onClientDisconnected: (() -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CameraStreamSender"

        private const val OMT_FRAME_METADATA = 1
        private const val OMT_FRAME_VIDEO = 2
        private const val OMT_FRAME_AUDIO = 4
        private const val OMT_HEADER_SIZE = 16
        private const val OMT_VIDEO_EXT_HEADER_SIZE = 32
        private const val OMT_AUDIO_EXT_HEADER_SIZE = 24
        private const val CODEC_VMX1 = 0x31584D56
        private const val CODEC_NV12 = 0x3231564E
        private const val CODEC_FPA1 = 0x31415046 // "FPA1" — Float Planar Audio

        // Audio: 48kHz stereo 32-bit float, planar (LLLL...RRRR...), ~960 samples/ch at 50fps
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_BITS = 32
        private const val AUDIO_SAMPLES_PER_CHANNEL = 960 // 48000 / 50
    }

    private data class ClientChannel(
        val socket: Socket,
        val output: OutputStream,
        val subscribedVideo: AtomicBoolean = AtomicBoolean(false),
        val subscribedAudio: AtomicBoolean = AtomicBoolean(false)
    )

    @Volatile private var serverSocket: ServerSocket? = null
    private val channels = CopyOnWriteArrayList<ClientChannel>()

    @Volatile private var vmxHandle: Long = 0L
    private var vmxWidth: Int = 0
    private var vmxHeight: Int = 0
    private var vmxOutputBuf: ByteArray? = null
    @Volatile private var vmxEncodeLogged = false
    @Volatile private var frameCount = 0L
    @Volatile private var noClientLogCount = 0

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var encodeThread: Thread? = null
    private var audioThread: Thread? = null

    // --- Double-buffer for producer (camera) → consumer (encoder) ---
    private class FrameBuffer {
        var yData: ByteArray? = null
        var uvData: ByteArray? = null
        var width = 0
        var height = 0
        var yStride = 0
        var timestamp = 0L
        var ready = false
    }

    private val frameLock = ReentrantLock()
    private val frameAvailable = frameLock.newCondition()
    private val pendingFrame = FrameBuffer()

    private var fpsFrameCount = 0L
    private var fpsLastLogTime = 0L

    val isStreaming: Boolean
        get() = running.get() && channels.any { it.socket.isConnected }

    fun start() {
        if (running.getAndSet(true)) return
        encodeThread = thread(name = "OmtEncodeSend") { encodeSendLoop() }
        acceptThread = thread(name = "OmtAccept") {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                try {
                    server.bind(InetSocketAddress("0.0.0.0", port))
                } catch (e: Exception) { running.set(false); throw e }
                serverSocket = server
                Log.i(TAG, "Listening on 0.0.0.0:$port")
                onServerListening?.invoke()
                thread(name = "OmtSelfTest") {
                    try {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2000) }
                        Log.i(TAG, "Self-connect OK: port $port reachable")
                    } catch (e: Exception) { Log.w(TAG, "Self-connect FAILED: $e") }
                }
                while (running.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        if (client.inetAddress?.isLoopbackAddress == true) { client.closeQuietly(); continue }
                        Log.i(TAG, "ACCEPTED connection from ${client.inetAddress}:${client.port}")
                        handleNewClient(client)
                    } catch (e: Exception) { if (running.get()) onError?.invoke(e) }
                }
            } catch (e: Exception) { if (running.get()) onError?.invoke(e) }
            finally { serverSocket?.closeQuietly(); serverSocket = null }
        }
        // Start audio capture if RECORD_AUDIO permission is available
        if (context != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) {
            audioThread = thread(name = "OmtAudioCapture") { audioCaptureLoop() }
            Log.i(TAG, "Audio capture started")
        } else {
            Log.i(TAG, "Audio capture disabled (no RECORD_AUDIO permission)")
        }
    }

    private fun handleNewClient(client: Socket) {
        client.soTimeout = 5000
        client.tcpNoDelay = true
        client.sendBufferSize = 512 * 1024
        val channel = ClientChannel(
            socket = client,
            output = BufferedOutputStream(client.getOutputStream(), 256 * 1024)
        )
        channels.add(channel)
        onClientConnected?.invoke(client.inetAddress?.hostAddress ?: "?")
        Log.i(TAG, "Client connected (channels=${channels.size})")

        // Send initial metadata immediately (mimics vMix server behavior)
        try { synchronized(channel.output) {
            sendMetadataToChannel(channel, "<OMTInfo ProductName=\"OMT Camera\" Manufacturer=\"OMT\" />")
            sendMetadataToChannel(channel, "<OMTTally Preview=\"false\" Program=\"false\" />")
        }} catch (e: Exception) {
            Log.w(TAG, "Initial metadata send failed: ${e.message}")
        }

        thread(name = "OmtClientReader") { readClientLoop(channel) }
    }

    private fun readClientLoop(channel: ClientChannel) {
        val input = try { DataInputStream(channel.socket.getInputStream()) }
        catch (e: Exception) { removeChannel(channel); return }
        val headerBuf = ByteArray(OMT_HEADER_SIZE)
        try {
            while (running.get() && channel.socket.isConnected) {
                try {
                    var got = 0
                    while (got < OMT_HEADER_SIZE) {
                        val n = input.read(headerBuf, got, OMT_HEADER_SIZE - got)
                        if (n <= 0) { removeChannel(channel); return }
                        got += n
                    }
                    val version = headerBuf[0].toInt() and 0xff
                    val dataLen = readIntLE(headerBuf)
                    if (version != 1 || dataLen <= 0 || dataLen > 1024 * 1024) {
                        skipBytes(input, dataLen.coerceIn(0, 65536)); continue
                    }
                    val payload = ByteArray(dataLen)
                    var read = 0
                    while (read < dataLen) {
                        val n = input.read(payload, read, dataLen - read)
                        if (n <= 0) { removeChannel(channel); return }
                        read += n
                    }
                    val frameType = headerBuf[1].toInt() and 0xff
                    if (frameType == OMT_FRAME_METADATA) {
                        val len = payload.indexOfFirst { it == 0.toByte() }.let { if (it < 0) payload.size else it }
                        val text = String(payload, 0, len, Charsets.UTF_8)
                        Log.d(TAG, "Metadata: ${text.take(80)}")
                        if (text.contains("Subscribe", ignoreCase = true) && text.contains("Video", ignoreCase = true)) {
                            channel.subscribedVideo.set(true)
                            Log.d(TAG, "Subscribe Video from ${channel.socket.inetAddress}")
                        }
                        if (text.contains("Subscribe", ignoreCase = true) && text.contains("Audio", ignoreCase = true)) {
                            channel.subscribedAudio.set(true)
                            Log.d(TAG, "Subscribe Audio from ${channel.socket.inetAddress}")
                            // Respond immediately to prevent vMix from resetting idle audio channel
                            try { synchronized(channel.output) {
                                sendMetadataToChannel(channel, "<OMTTally Preview=\"false\" Program=\"false\" />")
                            }} catch (_: Exception) {}
                        }
                    }
                } catch (_: SocketTimeoutException) { }
            }
        } catch (e: Exception) {
            if (running.get() && e.message?.contains("closed") != true) Log.w(TAG, "Client read: ${e.message}")
        } finally { removeChannel(channel) }
    }

    private fun removeChannel(channel: ClientChannel) {
        if (channels.remove(channel)) {
            channel.socket.closeQuietly()
            if (channels.none { it.subscribedVideo.get() }) onClientDisconnected?.invoke()
        }
    }

    fun stop() {
        running.set(false)
        frameLock.withLock { frameAvailable.signalAll() }
        encodeThread?.join(2000); encodeThread = null
        audioThread?.join(2000); audioThread = null
        VmxEncoder.destroy(vmxHandle); vmxHandle = 0L
        vmxEncodeLogged = false; frameCount = 0L; noClientLogCount = 0; fpsFrameCount = 0L
        vmxOutputBuf = null
        channels.forEach { it.socket.closeQuietly() }; channels.clear()
        serverSocket?.closeQuietly(); serverSocket = null
        acceptThread?.join(1000); acceptThread = null
        onClientDisconnected?.invoke()
    }

    fun sendFrame(image: ImageProxy) {
        if (image.format != ImageFormat.YUV_420_888) return
        val videoChannels = channels.filter { it.subscribedVideo.get() && it.socket.isConnected }
        if (videoChannels.isEmpty()) {
            if (++noClientLogCount <= 3 || noClientLogCount % 90 == 0)
                Log.i(TAG, "No video clients (channels=${channels.size})")
            return
        }
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        frameLock.withLock {
            val ySize = width * height
            val uvSize = width * (height / 2)
            if (pendingFrame.yData == null || pendingFrame.yData!!.size != ySize)
                pendingFrame.yData = ByteArray(ySize)
            if (pendingFrame.uvData == null || pendingFrame.uvData!!.size != uvSize)
                pendingFrame.uvData = ByteArray(uvSize)

            val yBuf = yPlane.buffer.duplicate()
            val rowStride = yPlane.rowStride
            val yArr = pendingFrame.yData!!
            if (rowStride == width) {
                yBuf.get(yArr, 0, ySize)
            } else {
                for (row in 0 until height) {
                    yBuf.position(row * rowStride)
                    yBuf.get(yArr, row * width, width)
                }
            }
            fillNV12UVPlane(uPlane, vPlane, width, height, pendingFrame.uvData!!)
            pendingFrame.width = width
            pendingFrame.height = height
            pendingFrame.yStride = width
            pendingFrame.timestamp = System.nanoTime() / 100
            pendingFrame.ready = true
            frameAvailable.signal()
        }
    }

    private fun encodeSendLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var localY: ByteArray? = null
        var localUV: ByteArray? = null
        var localW = 0; var localH = 0; var localTimestamp = 0L
        val hdr = ByteBuffer.allocate(OMT_HEADER_SIZE + OMT_VIDEO_EXT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var encodeTimeTotal = 0L

        while (running.get()) {
            frameLock.withLock {
                while (!pendingFrame.ready && running.get()) frameAvailable.await()
                if (!running.get()) return
                val tmpY = localY; val tmpUV = localUV
                localY = pendingFrame.yData; localUV = pendingFrame.uvData
                localW = pendingFrame.width; localH = pendingFrame.height
                localTimestamp = pendingFrame.timestamp
                pendingFrame.yData = tmpY; pendingFrame.uvData = tmpUV
                pendingFrame.ready = false
            }
            if (localY == null || localUV == null) continue
            val width = localW; val height = localH
            // Send video to all subscribed video clients. (Previously limited to 1 to reduce
            // vMix dual-connection bandwidth; re-enabled for multi-client support.)
            val videoChannels = channels.filter { it.subscribedVideo.get() && it.socket.isConnected }
            if (videoChannels.isEmpty()) continue

            try {
                val encStart = System.nanoTime()
                var vmxPayloadLen = -1

                if (VmxEncoder.isAvailable()) {
                    if (vmxHandle == 0L || vmxWidth != width || vmxHeight != height) {
                        VmxEncoder.destroy(vmxHandle)
                        vmxHandle = VmxEncoder.create(width, height)
                        vmxWidth = width; vmxHeight = height
                        vmxOutputBuf = ByteArray(width * height * 2)
                        if (vmxHandle == 0L) Log.w(TAG, "VMX create failed ${width}x$height")
                    }
                    if (vmxHandle != 0L && vmxOutputBuf != null) {
                        vmxPayloadLen = VmxEncoder.encodeInto(
                            vmxHandle, localY!!, width, localUV!!, width, vmxOutputBuf!!
                        )
                        if (vmxPayloadLen < 0) {
                            Log.w(TAG, "VMX encode failed ${width}x$height")
                        } else if (!vmxEncodeLogged) {
                            vmxEncodeLogged = true
                            val encMs = (System.nanoTime() - encStart) / 1_000_000
                            Log.d(TAG, "VMX encoding OK: ${width}x$height, $vmxPayloadLen bytes, encode=${encMs}ms, cpus=${Runtime.getRuntime().availableProcessors()}")
                        }
                    }
                }
                val encMs = (System.nanoTime() - encStart) / 1_000_000
                encodeTimeTotal += encMs

                val useVmx = vmxPayloadLen > 0
                val codec = if (useVmx) CODEC_VMX1 else CODEC_NV12

                hdr.clear()
                hdr.put(1); hdr.put(OMT_FRAME_VIDEO.toByte())
                hdr.putLong(localTimestamp); hdr.putShort(0); hdr.putInt(0)
                hdr.putInt(codec); hdr.putInt(width); hdr.putInt(height)
                hdr.putInt(targetFps); hdr.putInt(1)
                hdr.putFloat(16f / 9f); hdr.putInt(0); hdr.putInt(709)
                val hdrBytes = hdr.array()

                if (useVmx) {
                    val dataLength = OMT_VIDEO_EXT_HEADER_SIZE + vmxPayloadLen
                    writeIntLE(hdrBytes, 12, dataLength)
                    for (ch in videoChannels) {
                        try { synchronized(ch.output) {
                            ch.output.write(hdrBytes, 0, 48)
                            ch.output.write(vmxOutputBuf!!, 0, vmxPayloadLen)
                            ch.output.flush()
                        }} catch (e: Exception) { handleSendError(ch, e) }
                    }
                } else {
                    val ySize = width * height; val uvSize = width * (height / 2)
                    val dataLength = OMT_VIDEO_EXT_HEADER_SIZE + ySize + uvSize
                    writeIntLE(hdrBytes, 12, dataLength)
                    for (ch in videoChannels) {
                        try { synchronized(ch.output) {
                            ch.output.write(hdrBytes, 0, 48)
                            ch.output.write(localY, 0, ySize)
                            ch.output.write(localUV, 0, uvSize)
                            ch.output.flush()
                        }} catch (e: Exception) { handleSendError(ch, e) }
                    }
                }

                frameCount++; fpsFrameCount++
                val now = System.nanoTime()
                if (fpsLastLogTime == 0L) fpsLastLogTime = now
                val elapsed = now - fpsLastLogTime
                if (elapsed >= 3_000_000_000L) {
                    val fps = fpsFrameCount * 1_000_000_000.0 / elapsed
                    val avgEnc = if (fpsFrameCount > 0) encodeTimeTotal / fpsFrameCount else 0L
                    Log.i(TAG, "FPS: %.1f | ${width}x$height ${if (useVmx) "VMX1" else "NV12"} | enc=${avgEnc}ms | ${videoChannels.size} client(s) | frame $frameCount".format(fps))
                    fpsFrameCount = 0; fpsLastLogTime = now; encodeTimeTotal = 0L

                    // Send metadata keepalive to channels NOT receiving video
                    val idleChannels = channels.filter { it.socket.isConnected } - videoChannels.toSet()
                    for (ch in idleChannels) {
                        try { synchronized(ch.output) {
                            sendMetadataToChannel(ch, "<OMTTally Preview=\"false\" Program=\"false\" />")
                        }} catch (e: Exception) { handleSendError(ch, e) }
                    }
                }
            } catch (e: Exception) { onError?.invoke(e) }
        }
    }

    // ---- Audio capture ----

    @Suppress("MissingPermission")
    private fun audioCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT
        val minBuf = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, audioFormat)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed: $minBuf")
            return
        }
        val bufSize = maxOf(minBuf, AUDIO_SAMPLES_PER_CHANNEL * AUDIO_CHANNELS * 4 * 4)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE, channelConfig, audioFormat, bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed: ${e.message}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            recorder.release()
            return
        }

        recorder.startRecording()
        Log.i(TAG, "AudioRecord started: ${AUDIO_SAMPLE_RATE}Hz ${AUDIO_CHANNELS}ch float32-planar bufSize=$bufSize")

        // AudioRecord delivers interleaved stereo: [L0 R0 L1 R1 ...]
        val interleavedBuf = FloatArray(AUDIO_SAMPLES_PER_CHANNEL * AUDIO_CHANNELS)
        // OMT/vMix uses planar float: [L0 L1 ... L959][R0 R1 ... R959]
        val planarBuf = ByteBuffer.allocate(AUDIO_SAMPLES_PER_CHANNEL * AUDIO_CHANNELS * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        val audioHdr = ByteBuffer.allocate(OMT_HEADER_SIZE + OMT_AUDIO_EXT_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        var audioLogCount = 0

        try {
            while (running.get()) {
                val read = recorder.read(interleavedBuf, 0, interleavedBuf.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                val audioChannels = channels.filter {
                    it.subscribedAudio.get() && it.socket.isConnected
                }
                if (audioChannels.isEmpty()) continue

                val samplesPerCh = read / AUDIO_CHANNELS
                val payloadBytes = samplesPerCh * AUDIO_CHANNELS * 4
                val dataLen = OMT_AUDIO_EXT_HEADER_SIZE + payloadBytes

                // Build OMT header (16 bytes) + audio ext header (24 bytes)
                audioHdr.clear()
                audioHdr.put(1) // version
                audioHdr.put(OMT_FRAME_AUDIO.toByte()) // frame type 4
                audioHdr.putLong(System.nanoTime() / 100) // timestamp
                audioHdr.putShort(0) // reserved
                audioHdr.putInt(dataLen)
                // Audio ext header (24 bytes) — vMix layout: codec, sampleRate, samplesPerChannel, channels, bitsPerSample, reserved
                audioHdr.putInt(CODEC_FPA1)          // FPA1 = Float Planar Audio
                audioHdr.putInt(AUDIO_SAMPLE_RATE)    // 48000
                audioHdr.putInt(samplesPerCh)         // samples per channel (vMix expects this at offset 8)
                audioHdr.putInt(AUDIO_CHANNELS)       // 2
                audioHdr.putInt(AUDIO_BITS)           // 32
                audioHdr.putInt(0)                    // reserved
                val hdrBytes = audioHdr.array()

                // De-interleave: [L0 R0 L1 R1 ...] → planar [L0 L1 ... Ln][R0 R1 ... Rn]
                planarBuf.clear()
                for (i in 0 until samplesPerCh) planarBuf.putFloat(interleavedBuf[i * 2])       // left
                for (i in 0 until samplesPerCh) planarBuf.putFloat(interleavedBuf[i * 2 + 1])   // right
                val payloadArr = planarBuf.array()

                if (audioLogCount++ < 3) {
                    Log.i(TAG, "Audio send: ${samplesPerCh}samp/ch ${payloadBytes}B planar FPA1 to ${audioChannels.size} ch")
                }

                for (ch in audioChannels) {
                    try {
                        synchronized(ch.output) {
                            ch.output.write(hdrBytes)
                            ch.output.write(payloadArr, 0, payloadBytes)
                            ch.output.flush()
                        }
                    } catch (e: Exception) { handleSendError(ch, e) }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Audio capture error: ${e.message}")
        } finally {
            recorder.stop()
            recorder.release()
            Log.i(TAG, "AudioRecord stopped")
        }
    }

    // ---- Helpers ----

    private fun sendMetadataToChannel(ch: ClientChannel, xml: String) {
        val payload = xml.toByteArray(Charsets.UTF_8)
        val hdr = ByteBuffer.allocate(OMT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(1); hdr.put(OMT_FRAME_METADATA.toByte())
        hdr.putLong(0); hdr.putShort(0); hdr.putInt(payload.size)
        ch.output.write(hdr.array()); ch.output.write(payload); ch.output.flush()
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xff).toByte()
        buf[offset + 1] = ((value shr 8) and 0xff).toByte()
        buf[offset + 2] = ((value shr 16) and 0xff).toByte()
        buf[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun readIntLE(buf: ByteArray): Int =
        (buf[12].toInt() and 0xFF) or ((buf[13].toInt() and 0xFF) shl 8) or
        ((buf[14].toInt() and 0xFF) shl 16) or ((buf[15].toInt() and 0xFF) shl 24)

    private fun skipBytes(input: java.io.DataInputStream, count: Int) {
        val skip = ByteArray(minOf(count, 8192))
        var remaining = count
        while (remaining > 0) { val n = input.read(skip, 0, minOf(remaining, skip.size)); if (n <= 0) break; remaining -= n }
    }

    private fun handleSendError(ch: ClientChannel, e: Exception) {
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("broken pipe") || msg.contains("connection reset") || msg.contains("socket closed") || msg.contains("closed"))
            removeChannel(ch) else onError?.invoke(e)
    }

    private fun fillNV12UVPlane(uPlane: ImageProxy.PlaneProxy, vPlane: ImageProxy.PlaneProxy, width: Int, height: Int, out: ByteArray) {
        val uvHeight = height / 2; val uvWidth = width / 2
        val uBuf = uPlane.buffer.duplicate(); val uPixelStride = uPlane.pixelStride; val uRowStride = uPlane.rowStride
        val vBuf = vPlane.buffer.duplicate(); val vPixelStride = vPlane.pixelStride; val vRowStride = vPlane.rowStride
        if (uPixelStride == 2 && vPixelStride == 2) {
            for (row in 0 until uvHeight) {
                uBuf.position(row * uRowStride)
                uBuf.get(out, row * width, (uvWidth * 2).coerceAtMost(uBuf.remaining()))
            }
        } else {
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uIdx = row * uRowStride + col * uPixelStride
                    val vIdx = row * vRowStride + col * vPixelStride
                    val outIdx = row * width + col * 2
                    out[outIdx] = if (uIdx < uBuf.limit()) uBuf.get(uIdx) else 0
                    out[outIdx + 1] = if (vIdx < vBuf.limit()) vBuf.get(vIdx) else 0
                }
            }
        }
    }
}

private fun ServerSocket.closeQuietly() { try { close() } catch (_: Exception) {} }
private fun Socket.closeQuietly() { try { close() } catch (_: Exception) {} }
