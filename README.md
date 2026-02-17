# OMT Camera (Android)

Android app that streams the device camera and microphone over the local network using the **Open Media Transport (OMT)** protocol. The phone acts as an OMT source; vMix and other OMT receivers connect to it.

## Features

- **Camera**: VMX-encoded 1080p/720p/540p via CameraX; front/back camera switch.
- **Microphone**: 48 kHz stereo FPA1 (float planar) audio — same format as vMix OMT.
- **Discovery**: DNS-SD (`_omt._tcp`). In vMix: Add Input → OMT → phone appears in the list.
- **OMT Viewer**: Built-in viewer to receive and display OMT streams (e.g. from vMix) with video + audio.
- **Android TV**: D-pad support; camera disabled on TV.

## Requirements

- Android 7+ (API 24+)
- Same Wi‑Fi as vMix/PC
- **libvmx.so** in `app/src/main/jniLibs/arm64-v8a/` for VMX video encoding (see [BUILD_VMX.md](BUILD_VMX.md))

Without libvmx, the source appears in vMix but video stays black. Audio works with or without libvmx.

**Note on camera audio**: The app sends FPA1 audio (48 kHz stereo) to vMix. If you do not hear it, check vMix’s audio routing: ensure the OMT input’s audio is assigned to the mix (e.g. via the input’s audio bus or mixer).

## How to run

1. Open the project in **Android Studio**, sync Gradle.
2. Add `libvmx.so` (and `libc++_shared.so`) per [BUILD_VMX.md](BUILD_VMX.md).
3. Connect an Android device on the same Wi‑Fi as vMix.
4. Build and run. Allow **Camera** and **Microphone** when prompted.
5. Tap **Start stream**. Default port **6500**.
6. In vMix: **Add Input → OMT** → your phone appears; double‑click to add.

## OMT Viewer

Use the viewer to receive OMT streams (e.g. from vMix). In the launcher, tap **Viewer**, choose a source from the list, and connect. Video and audio are played back.

## Licence

MIT.
