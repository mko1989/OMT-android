# What Fixed OMT Camera Audio in vMix

## Problem
The app sent FPA1 audio (48 kHz stereo, float planar) and logs showed "Audio send: 960samp/ch 7680B planar FPA1 to N ch", but vMix produced no sound.

## Root Cause
The audio extended header did not match the official OMT protocol. At offset 16 we sent **bitsPerSample (32)** instead of **ActiveChannels** (bitfield).

Per [PROTOCOL.md](https://github.com/openmediatransport/libomtnet/blob/master/PROTOCOL.md):

**AUDIO EXTENDED HEADER (24 bytes):**
| Offset | Field           | Type   | Description                                                    |
|--------|-----------------|--------|----------------------------------------------------------------|
| 0      | Codec           | INT32  | FPA1 = 32bit float planar                                      |
| 4      | SampleRate      | INT32  | e.g. 48000                                                     |
| 8      | SamplesPerChannel | INT32 | samples per channel in this frame                              |
| 12     | Channels        | INT32  | number of channels (e.g. 2 for stereo)                         |
| 16     | **ActiveChannels** | UINT32 | Bitfield: which channels have data (0x03 = both L+R active)  |
| 20     | Reserved1       | INT32  | reserved                                                        |

## Fix Applied

In `CameraStreamSender.kt`, `audioCaptureLoop()`:

**Before (wrong):**
```kotlin
audioHdr.putInt(AUDIO_BITS)           // 32 — wrong field at offset 16
```

**After (correct):**
```kotlin
audioHdr.putInt(0x03)                 // ActiveChannels bitfield: bits 0+1 = both L+R active
```

FPA1 implies 32-bit float; bitsPerSample is not sent. vMix was likely rejecting or misinterpreting audio because ActiveChannels was invalid.

---

## File changed
- `app/src/main/java/com/omt/camera/CameraStreamSender.kt` — `audioCaptureLoop()` audio extended header
