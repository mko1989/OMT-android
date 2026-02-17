/**
 * JNI wrapper for libvmx (VMX codec). Uses dlopen/dlsym so libvmx.so is optional.
 * Supports both encoding (camera→stream) and decoding (stream→viewer).
 */
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdint>
#include <algorithm>

#define LOG_TAG "VmxJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void* s_libvmx = nullptr;

typedef struct { int width; int height; } VMX_SIZE;
typedef unsigned char BYTE;
enum { VMX_PROFILE_OMT_SQ = 166, VMX_COLORSPACE_BT709 = 709 };
enum VMX_ERR { VMX_ERR_OK = 0 };

// Encode functions
typedef void* (*VMX_Create_t)(VMX_SIZE, int profile, int colorSpace);
typedef void (*VMX_Destroy_t)(void*);
typedef int (*VMX_EncodeNV12_t)(void*, BYTE* srcY, int srcStrideY, BYTE* srcUV, int srcStrideUV, int interlaced);
typedef int (*VMX_SaveTo_t)(void*, BYTE* dst, int maxLen);
typedef int (*VMX_GetThreads_t)(void*);
typedef void (*VMX_SetThreads_t)(void*, int numThreads);

// Decode functions
typedef int (*VMX_LoadFrom_t)(void*, BYTE* data, int dataLen);
typedef int (*VMX_DecodeBGRA_t)(void*, BYTE* dst, int stride);

static VMX_Create_t fp_VMX_Create = nullptr;
static VMX_Destroy_t fp_VMX_Destroy = nullptr;
static VMX_EncodeNV12_t fp_VMX_EncodeNV12 = nullptr;
static VMX_SaveTo_t fp_VMX_SaveTo = nullptr;
static VMX_GetThreads_t fp_VMX_GetThreads = nullptr;
static VMX_SetThreads_t fp_VMX_SetThreads = nullptr;
static VMX_LoadFrom_t fp_VMX_LoadFrom = nullptr;
static VMX_DecodeBGRA_t fp_VMX_DecodeBGRA = nullptr;

// Reusable encode output buffer
static BYTE* s_outBuf = nullptr;
static int s_outBufSize = 0;

static bool loadLibVmx(JNIEnv* env) {
    if (s_libvmx) return true;
    s_libvmx = dlopen("libvmx.so", RTLD_NOW);
    if (!s_libvmx) {
        LOGE("dlopen libvmx.so failed: %s", dlerror());
        return false;
    }
    fp_VMX_Create = (VMX_Create_t)dlsym(s_libvmx, "VMX_Create");
    fp_VMX_Destroy = (VMX_Destroy_t)dlsym(s_libvmx, "VMX_Destroy");
    fp_VMX_EncodeNV12 = (VMX_EncodeNV12_t)dlsym(s_libvmx, "VMX_EncodeNV12");
    fp_VMX_SaveTo = (VMX_SaveTo_t)dlsym(s_libvmx, "VMX_SaveTo");
    fp_VMX_GetThreads = (VMX_GetThreads_t)dlsym(s_libvmx, "VMX_GetThreads");
    fp_VMX_SetThreads = (VMX_SetThreads_t)dlsym(s_libvmx, "VMX_SetThreads");
    fp_VMX_LoadFrom = (VMX_LoadFrom_t)dlsym(s_libvmx, "VMX_LoadFrom");
    fp_VMX_DecodeBGRA = (VMX_DecodeBGRA_t)dlsym(s_libvmx, "VMX_DecodeBGRA");
    if (!fp_VMX_Create || !fp_VMX_Destroy || !fp_VMX_EncodeNV12 || !fp_VMX_SaveTo) {
        LOGE("dlsym VMX encode functions failed");
        dlclose(s_libvmx);
        s_libvmx = nullptr;
        return false;
    }
    LOGI("libvmx loaded (threads: %s, decode: %s)",
         fp_VMX_SetThreads ? "yes" : "no",
         fp_VMX_LoadFrom ? "yes" : "no");
    return true;
}

// Clamp helper
static inline int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

extern "C" {

// ====================== Encoder JNI ======================

JNIEXPORT jboolean JNICALL
Java_com_omt_camera_VmxEncoder_nativeInit(JNIEnv* env, jclass) {
    return loadLibVmx(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_omt_camera_VmxEncoder_nativeCreate(JNIEnv* env, jclass, jint width, jint height, jint numThreads) {
    if (!loadLibVmx(env)) return 0;
    VMX_SIZE size = { width, height };
    void* inst = fp_VMX_Create(size, VMX_PROFILE_OMT_SQ, VMX_COLORSPACE_BT709);
    if (inst && fp_VMX_SetThreads && numThreads > 0) {
        int before = fp_VMX_GetThreads ? fp_VMX_GetThreads(inst) : -1;
        fp_VMX_SetThreads(inst, numThreads);
        int after = fp_VMX_GetThreads ? fp_VMX_GetThreads(inst) : -1;
        LOGI("VMX encoder %dx%d threads: %d -> %d (requested %d)", width, height, before, after, numThreads);
    }
    int needed = width * height * 2;
    if (needed > s_outBufSize) {
        delete[] s_outBuf;
        s_outBuf = new BYTE[needed];
        s_outBufSize = needed;
    }
    return (jlong)(uintptr_t)inst;
}

JNIEXPORT void JNICALL
Java_com_omt_camera_VmxEncoder_nativeDestroy(JNIEnv* env, jclass, jlong handle) {
    if (handle && fp_VMX_Destroy)
        fp_VMX_Destroy((void*)(uintptr_t)handle);
}

/**
 * Zero-allocation encode: writes VMX output directly into a pre-allocated Java byte array.
 * Returns number of bytes written, or -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_omt_camera_VmxEncoder_nativeEncodeInto(JNIEnv* env, jclass, jlong handle,
        jbyteArray jY, jint strideY, jbyteArray jUV, jint strideUV,
        jbyteArray jOutput, jint maxOutputLen) {
    if (!handle || !fp_VMX_EncodeNV12 || !fp_VMX_SaveTo) return -1;
    if (!jY || !jUV || !jOutput) return -1;

    jbyte* yPtr = env->GetByteArrayElements(jY, nullptr);
    jbyte* uvPtr = env->GetByteArrayElements(jUV, nullptr);
    if (!yPtr || !uvPtr) {
        if (yPtr) env->ReleaseByteArrayElements(jY, yPtr, JNI_ABORT);
        if (uvPtr) env->ReleaseByteArrayElements(jUV, uvPtr, JNI_ABORT);
        return -1;
    }

    int err = fp_VMX_EncodeNV12((void*)(uintptr_t)handle,
            reinterpret_cast<BYTE*>(yPtr), strideY,
            reinterpret_cast<BYTE*>(uvPtr), strideUV, 0);
    env->ReleaseByteArrayElements(jY, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(jUV, uvPtr, JNI_ABORT);
    if (err != VMX_ERR_OK) return -1;

    // Write directly into the Java output array via the reusable native buffer
    if (!s_outBuf || s_outBufSize <= 0) return -1;
    int written = fp_VMX_SaveTo((void*)(uintptr_t)handle, s_outBuf, s_outBufSize);
    if (written <= 0 || written > s_outBufSize || written > maxOutputLen) return -1;

    env->SetByteArrayRegion(jOutput, 0, written, reinterpret_cast<const jbyte*>(s_outBuf));
    return written;
}

// ====================== Decoder JNI ======================

JNIEXPORT jboolean JNICALL
Java_com_omt_camera_VmxDecoder_nativeCanDecode(JNIEnv* env, jclass) {
    if (!loadLibVmx(env)) return JNI_FALSE;
    return (fp_VMX_LoadFrom && fp_VMX_DecodeBGRA) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_omt_camera_VmxDecoder_nativeCreate(JNIEnv* env, jclass, jint width, jint height, jint numThreads) {
    if (!loadLibVmx(env)) return 0;
    if (!fp_VMX_LoadFrom || !fp_VMX_DecodeBGRA) return 0;
    VMX_SIZE size = { width, height };
    void* inst = fp_VMX_Create(size, VMX_PROFILE_OMT_SQ, VMX_COLORSPACE_BT709);
    if (inst && fp_VMX_SetThreads && numThreads > 0) {
        fp_VMX_SetThreads(inst, numThreads);
        int after = fp_VMX_GetThreads ? fp_VMX_GetThreads(inst) : -1;
        LOGI("VMX decoder %dx%d threads: %d", width, height, after);
    }
    return (jlong)(uintptr_t)inst;
}

JNIEXPORT void JNICALL
Java_com_omt_camera_VmxDecoder_nativeDestroy(JNIEnv* env, jclass, jlong handle) {
    if (handle && fp_VMX_Destroy)
        fp_VMX_Destroy((void*)(uintptr_t)handle);
}

/**
 * Swap BGRA → RGBA in-place using 32-bit operations.
 * Swaps R and B channels while keeping G and A unchanged.
 */
static void swapBGRAtoRGBA(BYTE* buf, int numPixels) {
    uint32_t* p = reinterpret_cast<uint32_t*>(buf);
    for (int i = 0; i < numPixels; i++) {
        uint32_t v = p[i];
        // BGRA byte order: [B,G,R,A] → RGBA byte order: [R,G,B,A]
        p[i] = (v & 0xFF00FF00u) | ((v & 0xFFu) << 16) | ((v >> 16) & 0xFFu);
    }
}

/**
 * Load compressed VMX data and decode to RGBA in one call.
 * VMX_DecodeBGRA outputs BGRA; we swap to RGBA so Android's ARGB_8888
 * (which stores bytes as R,G,B,A on little-endian) renders correctly.
 * Returns true on success. dstRGBA must be width*height*4 bytes.
 */
JNIEXPORT jboolean JNICALL
Java_com_omt_camera_VmxDecoder_nativeDecodeFrame(JNIEnv* env, jclass, jlong handle,
        jbyteArray jVmxData, jint dataLen, jbyteArray jDstBGRA, jint width, jint height) {
    if (!handle || !fp_VMX_LoadFrom || !fp_VMX_DecodeBGRA) return JNI_FALSE;
    if (!jVmxData || !jDstBGRA) return JNI_FALSE;

    jbyte* vmxPtr = env->GetByteArrayElements(jVmxData, nullptr);
    if (!vmxPtr) return JNI_FALSE;

    int err = fp_VMX_LoadFrom((void*)(uintptr_t)handle,
            reinterpret_cast<BYTE*>(vmxPtr), dataLen);
    env->ReleaseByteArrayElements(jVmxData, vmxPtr, JNI_ABORT);
    if (err != VMX_ERR_OK) return JNI_FALSE;

    jbyte* dstPtr = env->GetByteArrayElements(jDstBGRA, nullptr);
    if (!dstPtr) return JNI_FALSE;

    int stride = width * 4;
    err = fp_VMX_DecodeBGRA((void*)(uintptr_t)handle,
            reinterpret_cast<BYTE*>(dstPtr), stride);
    if (err == VMX_ERR_OK) {
        swapBGRAtoRGBA(reinterpret_cast<BYTE*>(dstPtr), width * height);
    }
    env->ReleaseByteArrayElements(jDstBGRA, dstPtr, 0); // copy back
    return (err == VMX_ERR_OK) ? JNI_TRUE : JNI_FALSE;
}

// ====================== NV12 to BGRA converter ======================

/**
 * Convert NV12 (Y + interleaved UV) to RGBA using BT.709 coefficients.
 * Works without libvmx — pure C implementation for raw NV12 streams.
 * Outputs RGBA byte order to match Android's ARGB_8888 memory layout.
 */
JNIEXPORT void JNICALL
Java_com_omt_camera_VmxDecoder_nativeNv12ToBgra(JNIEnv* env, jclass,
        jbyteArray jY, jbyteArray jUV, jbyteArray jDst, jint width, jint height) {
    jbyte* yPtr = env->GetByteArrayElements(jY, nullptr);
    jbyte* uvPtr = env->GetByteArrayElements(jUV, nullptr);
    jbyte* dstPtr = env->GetByteArrayElements(jDst, nullptr);
    if (!yPtr || !uvPtr || !dstPtr) {
        if (yPtr) env->ReleaseByteArrayElements(jY, yPtr, JNI_ABORT);
        if (uvPtr) env->ReleaseByteArrayElements(jUV, uvPtr, JNI_ABORT);
        if (dstPtr) env->ReleaseByteArrayElements(jDst, dstPtr, JNI_ABORT);
        return;
    }

    BYTE* y = reinterpret_cast<BYTE*>(yPtr);
    BYTE* uv = reinterpret_cast<BYTE*>(uvPtr);
    BYTE* dst = reinterpret_cast<BYTE*>(dstPtr);

    // BT.709 coefficients (fixed point, shift 10)
    const int CY = 1192;  // 1.164 * 1024
    const int CRV = 1836; // 1.793 * 1024
    const int CGU = 218;  // 0.213 * 1024
    const int CGV = 546;  // 0.533 * 1024
    const int CBU = 2163; // 2.112 * 1024

    for (int row = 0; row < height; row++) {
        int uvRow = row >> 1;
        for (int col = 0; col < width; col++) {
            int yVal = (int)y[row * width + col] - 16;
            int uvCol = col & ~1;
            int uVal = (int)uv[uvRow * width + uvCol] - 128;
            int vVal = (int)uv[uvRow * width + uvCol + 1] - 128;

            int c = CY * yVal;
            int r = clamp((c + CRV * vVal) >> 10, 0, 255);
            int g = clamp((c - CGU * uVal - CGV * vVal) >> 10, 0, 255);
            int b = clamp((c + CBU * uVal) >> 10, 0, 255);

            int dstIdx = (row * width + col) * 4;
            dst[dstIdx]     = (BYTE)r; // RGBA byte order
            dst[dstIdx + 1] = (BYTE)g;
            dst[dstIdx + 2] = (BYTE)b;
            dst[dstIdx + 3] = 0xFF;
        }
    }

    env->ReleaseByteArrayElements(jY, yPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(jUV, uvPtr, JNI_ABORT);
    env->ReleaseByteArrayElements(jDst, dstPtr, 0); // copy back
}

} // extern "C"
