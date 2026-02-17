package com.omt.camera

import android.util.Log

object VmxEncoder {
    private const val TAG = "VmxEncoder"

    @Volatile
    private var available: Boolean? = null

    init {
        try {
            System.loadLibrary("omt_vmx_jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "omt_vmx_jni not loaded", e)
        }
    }

    @JvmStatic
    fun isAvailable(): Boolean {
        if (available != null) return available!!
        available = nativeInit()
        Log.d(TAG, "libvmx available: $available")
        return available!!
    }

    private external fun nativeInit(): Boolean
    private external fun nativeCreate(width: Int, height: Int, numThreads: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeEncodeInto(
        handle: Long, y: ByteArray, strideY: Int, uv: ByteArray, strideUV: Int,
        output: ByteArray, maxOutputLen: Int
    ): Int

    @JvmStatic
    fun create(width: Int, height: Int, numThreads: Int = Runtime.getRuntime().availableProcessors()): Long {
        if (!isAvailable()) return 0L
        if (width < 16 || height < 16 || width % 2 != 0) return 0L
        return nativeCreate(width, height, numThreads)
    }

    @JvmStatic
    fun destroy(handle: Long) {
        if (handle != 0L) nativeDestroy(handle)
    }

    /**
     * Zero-allocation encode: writes VMX output into [output] array.
     * Returns number of bytes written, or -1 on error.
     */
    @JvmStatic
    fun encodeInto(
        handle: Long,
        yArr: ByteArray, strideY: Int,
        uvArr: ByteArray, strideUV: Int,
        output: ByteArray
    ): Int {
        if (handle == 0L || !isAvailable()) return -1
        return nativeEncodeInto(handle, yArr, strideY, uvArr, strideUV, output, output.size)
    }
}
