package com.omt.camera

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Connects to an OMT source via TCP, subscribes to video + audio,
 * receives frames, decodes them, and delivers Bitmaps asynchronously.
 *
 * Video: receive thread decodes → atomic reference → render thread draws at display rate.
 * Audio: receive thread → AudioTrack write (non-blocking).
 */
class OmtStreamReceiver(
    private val host: String,
    private val port: Int,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "OmtStreamReceiver"
        private const val OMT_FRAME_METADATA = 1
        private const val OMT_FRAME_VIDEO = 2
        private const val OMT_FRAME_AUDIO = 4
        private const val OMT_HEADER_SIZE = 16
        private const val OMT_VIDEO_EXT_HEADER_SIZE = 32
        private const val OMT_AUDIO_EXT_HEADER_SIZE = 24
        private const val CODEC_VMX1 = 0x31584D56
        private const val CODEC_NV12 = 0x3231564E
        private const val CODEC_FPA1 = 0x31415046 // "FPA1" — Float Planar Audio
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var receiveThread: Thread? = null
    private var renderThread: Thread? = null

    // VMX decoder state
    private var vmxHandle = 0L
    private var vmxWidth = 0
    private var vmxHeight = 0

    // Reusable decode buffers
    private var bgraBuf: ByteArray? = null

    // Triple-buffered bitmap pool: receive thread takes from pool, writes pixels,
    // sets pending. Render thread takes pending, draws it, returns to pool.
    // This guarantees no bitmap is ever read and written simultaneously.
    private val bitmapPool = ConcurrentLinkedQueue<Bitmap>()
    private val pendingBitmap = AtomicReference<Bitmap?>(null)

    // Audio playback
    private var audioTrack: AudioTrack? = null
    private var audioSampleRate = 0
    private var audioChannels = 0

    // FPS measurement
    private var fpsCount = 0L
    private var fpsLastTime = 0L
    private var lastCodecName = ""
    private var lastWidth = 0
    private var lastHeight = 0

    fun start() {
        if (running.getAndSet(true)) return
        receiveThread = thread(name = "OmtReceive") { receiveLoop() }
        renderThread = thread(name = "OmtRender") { renderLoop() }
    }

    fun stop() {
        running.set(false)
        socket?.closeQuietly()
        receiveThread?.join(3000); receiveThread = null
        renderThread?.join(1000); renderThread = null
        VmxDecoder.destroy(vmxHandle); vmxHandle = 0L
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        pendingBitmap.getAndSet(null)?.recycle()
        var bmp = bitmapPool.poll()
        while (bmp != null) { bmp.recycle(); bmp = bitmapPool.poll() }
    }

    /**
     * Render loop: picks up the latest decoded bitmap and delivers it via onFrame.
     * Runs at display rate — never blocks the receive thread.
     */
    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (running.get()) {
            val bmp = pendingBitmap.getAndSet(null)
            if (bmp != null) {
                onFrame(bmp)
                bitmapPool.offer(bmp) // return to pool after render is done
                fpsCount++
                val now = System.nanoTime()
                if (fpsLastTime == 0L) fpsLastTime = now
                val elapsed = now - fpsLastTime
                if (elapsed >= 3_000_000_000L) {
                    val fps = fpsCount * 1_000_000_000.0 / elapsed
                    Log.i(TAG, "Viewer FPS: %.1f | ${lastWidth}x$lastHeight | $lastCodecName".format(fps))
                    onStatus("%.0f FPS — ${lastWidth}x$lastHeight".format(fps))
                    fpsCount = 0; fpsLastTime = now
                }
            } else {
                Thread.sleep(1)
            }
        }
    }

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        try {
            onStatus("Connecting to $host:$port…")
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            sock.tcpNoDelay = true
            sock.receiveBufferSize = 1024 * 1024
            socket = sock
            onStatus("Connected to $host:$port")
            Log.i(TAG, "Connected to $host:$port")

            val output = sock.getOutputStream()
            val input = DataInputStream(sock.getInputStream())

            sendMetadataFrame(output, "<OMTSubscribe Metadata=\"true\" />")
            sendMetadataFrame(output, "<OMTSubscribe Video=\"true\" />")
            sendMetadataFrame(output, "<OMTSubscribe Audio=\"true\" />")
            sendMetadataFrame(output, "<OMTSettings Quality=\"Default\" />")
            Log.i(TAG, "Sent subscription requests (video + audio)")
            onStatus("Subscribed — waiting for video…")

            val headerBuf = ByteArray(OMT_HEADER_SIZE)
            var unknownTypeLogCount = 0
            while (running.get() && sock.isConnected) {
                input.readFully(headerBuf)
                val version = headerBuf[0].toInt() and 0xFF
                val frameType = headerBuf[1].toInt() and 0xFF
                val dataLen = readIntLE(headerBuf, 12)

                if (version != 1 || dataLen <= 0 || dataLen > 16 * 1024 * 1024) {
                    // Bad header — skip remaining data if length is sane
                    if (dataLen in 1..65536) skipBytes(input, dataLen)
                    continue
                }

                val data = ByteArray(dataLen)
                input.readFully(data)

                when (frameType) {
                    OMT_FRAME_METADATA -> handleMetadata(data)
                    OMT_FRAME_VIDEO -> handleVideoFrame(data, dataLen)
                    OMT_FRAME_AUDIO -> try {
                        handleAudioFrame(data, dataLen)
                    } catch (e: Exception) {
                        if (++unknownTypeLogCount <= 5) {
                            Log.e(TAG, "Audio frame error: ${e.message}")
                        }
                    }
                    else -> {
                        if (++unknownTypeLogCount <= 5) {
                            Log.i(TAG, "Frame type=$frameType len=$dataLen (not video/audio/metadata)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "Receive error: ${e.message}")
                onError("Connection lost: ${e.message}")
            }
        } finally {
            socket?.closeQuietly(); socket = null
        }
    }

    private fun handleMetadata(data: ByteArray) {
        val len = data.indexOfFirst { it == 0.toByte() }.let { if (it < 0) data.size else it }
        val text = String(data, 0, len, Charsets.UTF_8)
        Log.d(TAG, "Metadata: ${text.take(120)}")
        if (text.contains("Tally", ignoreCase = true)) onStatus("Receiving from $host")
    }

    private fun handleVideoFrame(data: ByteArray, dataLen: Int) {
        if (dataLen < OMT_VIDEO_EXT_HEADER_SIZE) return
        val buf = ByteBuffer.wrap(data, 0, OMT_VIDEO_EXT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val codec = buf.int; val width = buf.int; val height = buf.int
        if (width <= 0 || height <= 0 || width > 7680 || height > 4320) return
        val payloadOffset = OMT_VIDEO_EXT_HEADER_SIZE
        val payloadLen = dataLen - OMT_VIDEO_EXT_HEADER_SIZE
        if (payloadLen <= 0) return

        val bgraSize = width * height * 4
        if (bgraBuf == null || bgraBuf!!.size != bgraSize) bgraBuf = ByteArray(bgraSize)

        val decoded = when (codec) {
            CODEC_VMX1 -> { lastCodecName = "VMX1"; decodeVmx(data, payloadOffset, payloadLen, width, height) }
            CODEC_NV12 -> { lastCodecName = "NV12"; decodeNv12(data, payloadOffset, payloadLen, width, height) }
            else -> { Log.w(TAG, "Unknown codec: 0x${Integer.toHexString(codec)}"); false }
        }
        if (!decoded) return
        lastWidth = width; lastHeight = height

        // Get a bitmap from the pool (or create one). Pool guarantees no
        // other thread is using it — render returns bitmaps after drawing.
        var bitmap = bitmapPool.poll()
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val bb = ByteBuffer.wrap(bgraBuf!!)
        bb.rewind()
        bitmap.copyPixelsFromBuffer(bb)

        // Swap into pending; return old (skipped) pending to pool
        val old = pendingBitmap.getAndSet(bitmap)
        if (old != null) bitmapPool.offer(old)
    }

    // ---- Audio ----

    private var audioLogCount = 0

    private fun handleAudioFrame(data: ByteArray, dataLen: Int) {
        if (dataLen < OMT_AUDIO_EXT_HEADER_SIZE) return

        val buf = ByteBuffer.wrap(data, 0, OMT_AUDIO_EXT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        val codec = buf.getInt(0)
        val sampleRate = buf.getInt(4)
        val field8 = buf.getInt(8)
        val field12 = buf.getInt(12)
        val field16 = buf.getInt(16)

        // Two header layouts exist:
        // Ours (camera): [codec, sampleRate, channels, bitsPerSample, samplesPerChannel, reserved]
        // vMix:         [codec, sampleRate, samplesPerChannel, channels, ???, reserved]
        // Detect: field8 in 1..8 = channels (our layout), else 256..4096 = samplesPerCh (vMix)
        val (channels: Int, bitsPerSample: Int, samplesPerCh: Int) = if (field8 in 1..8) {
            Triple(field8, if (field12 in 8..64) field12 else 32, field16)
        } else {
            Triple(field12, if (field16 in 8..64) field16 else 32, field8)
        }

        if (audioLogCount < 5) {
            audioLogCount++
            val codecStr = when (codec) { CODEC_FPA1 -> "FPA1" else -> "0x${Integer.toHexString(codec)}" }
            Log.i(TAG, "Audio: codec=$codecStr ${sampleRate}Hz ${channels}ch " +
                    "${bitsPerSample}bit ${samplesPerCh}samp/ch dataLen=$dataLen")
        }

        if (sampleRate !in 4000..192000 || channels !in 1..8 ||
            bitsPerSample !in 8..64 || samplesPerCh <= 0) {
            if (audioLogCount <= 8) {
                Log.w(TAG, "Audio: invalid params (rate=$sampleRate ch=$channels samp=$samplesPerCh), skipping")
            }
            return
        }

        val payloadOffset = OMT_AUDIO_EXT_HEADER_SIZE
        val payloadLen = dataLen - OMT_AUDIO_EXT_HEADER_SIZE
        if (payloadLen <= 0) return

        ensureAudioTrack(sampleRate, channels)

        if (codec == CODEC_FPA1 || bitsPerSample == 32) {
            // FPA1 = Float Planar Audio: [L0 L1 ... Ln][R0 R1 ... Rn]
            // Convert planar float32 to interleaved float for AudioTrack
            val floatBuf = ByteBuffer.wrap(data, payloadOffset,
                minOf(payloadLen, samplesPerCh * channels * 4))
                .order(ByteOrder.LITTLE_ENDIAN)
            val totalSamples = samplesPerCh * channels
            val interleaved = FloatArray(totalSamples)

            if (channels >= 2) {
                // De-planar: read left plane, then right plane, interleave
                for (i in 0 until samplesPerCh) {
                    interleaved[i * 2] = if (floatBuf.hasRemaining()) floatBuf.float else 0f
                }
                for (i in 0 until samplesPerCh) {
                    interleaved[i * 2 + 1] = if (floatBuf.hasRemaining()) floatBuf.float else 0f
                }
            } else {
                for (i in 0 until samplesPerCh) {
                    interleaved[i] = if (floatBuf.hasRemaining()) floatBuf.float else 0f
                }
            }
            audioTrack?.write(interleaved, 0, interleaved.size, AudioTrack.WRITE_NON_BLOCKING)
        } else if (bitsPerSample == 16) {
            val totalSamples = samplesPerCh * channels
            val pcm16 = ShortArray(totalSamples.coerceAtMost(payloadLen / 2))
            val bb = ByteBuffer.wrap(data, payloadOffset, pcm16.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in pcm16.indices) pcm16[i] = bb.short
            audioTrack?.write(pcm16, 0, pcm16.size)
        } else {
            if (audioLogCount <= 8) {
                Log.w(TAG, "Audio: unsupported codec=0x${Integer.toHexString(codec)} bits=$bitsPerSample")
            }
        }
    }

    private fun ensureAudioTrack(sampleRate: Int, channels: Int) {
        if (audioTrack != null && audioSampleRate == sampleRate && audioChannels == channels) return
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        audioSampleRate = sampleRate; audioChannels = channels

        val channelConfig = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) {
            Log.e(TAG, "AudioTrack: getMinBufferSize failed for ${sampleRate}Hz ${channels}ch")
            return
        }
        val bufSize = maxOf(minBuf, sampleRate * channels * 4 / 10) // 100ms float buffer

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            Log.i(TAG, "AudioTrack created: ${sampleRate}Hz ${channels}ch float bufSize=$bufSize")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack creation failed: ${e.message}")
            audioTrack = null
        }
    }

    // ---- Decode ----

    private fun decodeVmx(data: ByteArray, offset: Int, len: Int, width: Int, height: Int): Boolean {
        if (vmxHandle == 0L || vmxWidth != width || vmxHeight != height) {
            VmxDecoder.destroy(vmxHandle)
            vmxHandle = VmxDecoder.create(width, height)
            vmxWidth = width; vmxHeight = height
            if (vmxHandle == 0L) {
                Log.w(TAG, "VMX decoder not available for ${width}x$height — need libvmx.so")
                onStatus("Cannot decode VMX1 (libvmx.so missing)")
                return false
            }
            Log.i(TAG, "VMX decoder created: ${width}x$height")
        }
        val vmxBytes = if (offset == 0 && len == data.size) data
            else ByteArray(len).also { System.arraycopy(data, offset, it, 0, len) }
        return VmxDecoder.decodeFrame(vmxHandle, vmxBytes, len, bgraBuf!!, width, height)
    }

    private fun decodeNv12(data: ByteArray, offset: Int, len: Int, width: Int, height: Int): Boolean {
        val ySize = width * height; val uvSize = width * (height / 2)
        if (len < ySize + uvSize) { Log.w(TAG, "NV12 data too short: $len < ${ySize + uvSize}"); return false }
        val yArr = ByteArray(ySize); val uvArr = ByteArray(uvSize)
        System.arraycopy(data, offset, yArr, 0, ySize)
        System.arraycopy(data, offset + ySize, uvArr, 0, uvSize)
        VmxDecoder.nv12ToBgra(yArr, uvArr, bgraBuf!!, width, height)
        return true
    }

    // ---- Protocol helpers ----

    private fun sendMetadataFrame(output: OutputStream, xml: String) {
        val payload = xml.toByteArray(Charsets.UTF_8)
        val hdr = ByteBuffer.allocate(OMT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(1); hdr.put(OMT_FRAME_METADATA.toByte())
        hdr.putLong(0); hdr.putShort(0); hdr.putInt(payload.size)
        output.write(hdr.array()); output.write(payload); output.flush()
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset+1].toInt() and 0xFF) shl 8) or
        ((buf[offset+2].toInt() and 0xFF) shl 16) or ((buf[offset+3].toInt() and 0xFF) shl 24)

    private fun skipBytes(input: DataInputStream, count: Int) {
        val skip = ByteArray(minOf(count, 8192))
        var remaining = count
        while (remaining > 0) { val n = input.read(skip, 0, minOf(remaining, skip.size)); if (n <= 0) break; remaining -= n }
    }
}

private fun Socket.closeQuietly() { try { close() } catch (_: Exception) {} }
