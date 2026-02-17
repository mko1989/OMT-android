# Building libvmx for Android (to get video in vMix)

The app sends OMT wire format and can use **libvmx** to encode camera frames to VMX so vMix shows the picture. Without libvmx, the source still appears in vMix but video stays black.

## Option 1: Build libvmx with the Android NDK (recommended on Mac/Windows)

1. **Clone libvmx** (if you haven’t already). This project may have it under `libvmx/`, or clone from GitHub:
   ```bash
   git clone https://github.com/openmediatransport/libvmx.git
   cd libvmx
   ```

2. **Install the Android NDK** (Android Studio: SDK Manager → SDK Tools → NDK).

3. **Build for Android arm64** using the NDK script (from the **libvmx** repo). The script will use the NDK from your project’s `local.properties` (sdk.dir) or from `~/Library/Android/sdk/ndk` on Mac. If you don’t have the NDK installed, install it in Android Studio (SDK Manager → SDK Tools → NDK).
   ```bash
   cd libvmx/build
   chmod +x buildandroidarm64.sh
   ./buildandroidarm64.sh
   ```
   To force a specific NDK: `export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/27.1.12297018` (or your version) before running the script.
   This produces `libvmx.so` in `libvmx/build/`. The script also copies **libvmx.so** and **libc++_shared.so** into `OMT-camera-app/app/src/main/jniLibs/arm64-v8a/` if that path exists. libvmx depends on the NDK C++ runtime (`libc++_shared.so`); without it you get:
   `dlopen failed: library "libc++_shared.so" not found`.

4. If you built libvmx outside this project, copy both libraries into the app:
   ```bash
   mkdir -p /path/to/OMT-camera-app/app/src/main/jniLibs/arm64-v8a
   cp /path/to/libvmx/build/libvmx.so /path/to/OMT-camera-app/app/src/main/jniLibs/arm64-v8a/
   cp $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so \
      /path/to/OMT-camera-app/app/src/main/jniLibs/arm64-v8a/
   ```

**Why `buildlinuxarm64.sh` failed on your Mac:** That script is for a **Linux ARM64** host. On a Mac (x86_64), the compiler builds for the host, so the ARM-only code in `vmxcodec_arm.cpp` is not compiled and you get “Undefined symbols for architecture x86_64”. Use **buildandroidarm64.sh** with the NDK instead to target Android arm64.

4. **Rebuild the app** in Android Studio. When you run it, the JNI layer will load `libvmx.so` and encode frames to VMX; vMix should then show the video.

## Option 2: Use a prebuilt libvmx (if available)

If Open Media Transport or a third party provides a prebuilt **libvmx.so** for Android arm64-v8a, place it in:

- `app/src/main/jniLibs/arm64-v8a/libvmx.so`

Then rebuild and run the app.

## Verifying

- **Without libvmx**: The app runs; in vMix the source appears and connects, but the video is black. The in‑app hint says video will show once VMX encoding is added.
- **With libvmx**: After adding `libvmx.so` and rebuilding, the same source in vMix should show the camera picture.

## Troubleshooting

- If the app crashes on start, check logcat for `VmxJni` or `dlopen libvmx.so failed`. That usually means `libvmx.so` is missing, built for the wrong ABI (use arm64-v8a), or **libc++_shared.so is missing** – libvmx needs the NDK C++ runtime in `jniLibs/arm64-v8a/` too.
- If the source still shows black in vMix, confirm in logcat that you see `libvmx loaded` and that no encode errors are reported.
