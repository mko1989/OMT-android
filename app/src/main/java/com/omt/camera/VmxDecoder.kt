package com.omt.camera

import android.util.Log

/**
 * Decodes VMX compressed frames to RGBA pixels via native libvmx.
 * Native code performs BGRA→RGBA swap so output matches Android's ARGB_8888 memory layout.
 * Also provides NV12→RGBA conversion for raw NV12 streams (no libvmx needed).
 */
object VmxDecoder {
    private const val TAG = "VmxDecoder"

    @Volatile
    private var canDecode: Boolean? = null

    init {
        try {
            System.loadLibrary("omt_vmx_jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "omt_vmx_jni not loaded", e)
        }
    }

    @JvmStatic
    fun canDecode(): Boolean {
        if (canDecode != null) return canDecode!!
        canDecode = nativeCanDecode()
        Log.d(TAG, "VMX decode available: $canDecode")
        return canDecode!!
    }

    private external fun nativeCanDecode(): Boolean
    private external fun nativeCreate(width: Int, height: Int, numThreads: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeDecodeFrame(
        handle: Long, vmxData: ByteArray, dataLen: Int,
        dstBGRA: ByteArray, width: Int, height: Int
    ): Boolean
    private external fun nativeNv12ToBgra(
        y: ByteArray, uv: ByteArray, dst: ByteArray, width: Int, height: Int
    )

    @JvmStatic
    fun create(width: Int, height: Int): Long {
        if (!canDecode()) return 0L
        return nativeCreate(width, height, Runtime.getRuntime().availableProcessors())
    }

    @JvmStatic
    fun destroy(handle: Long) {
        if (handle != 0L) nativeDestroy(handle)
    }

    /**
     * Decode a VMX compressed frame into RGBA pixel array.
     * [dstBGRA] must be at least width*height*4 bytes.
     * Returns true on success.
     */
    @JvmStatic
    fun decodeFrame(handle: Long, vmxData: ByteArray, dataLen: Int,
                    dstBGRA: ByteArray, width: Int, height: Int): Boolean {
        if (handle == 0L) return false
        return nativeDecodeFrame(handle, vmxData, dataLen, dstBGRA, width, height)
    }

    /**
     * Convert raw NV12 (Y + interleaved UV) to RGBA pixels.
     * Does NOT require libvmx — works always.
     */
    @JvmStatic
    fun nv12ToBgra(y: ByteArray, uv: ByteArray, dst: ByteArray, width: Int, height: Int) {
        nativeNv12ToBgra(y, uv, dst, width, height)
    }
}
