# Performance Changes (aligned with GitHub alpha6)

Compared current code with [mko1989/OMT-android](https://github.com/mko1989/OMT-android) which had better video performance.

## Changes applied

### 1. MainActivity.kt
- **DEFAULT_RES_INDEX** → 0 (1080p). Was 1 (720p).
- **DEFAULT_FPS_INDEX** → 2 (30 fps). Was 3 (50 fps).

50 fps was likely too demanding; 30 fps matches the GitHub version and reduces encoder/camera load.

### 2. CameraStreamSender.kt
- **videoChannels** → send to all video clients (removed `.take(1)`). Matches GitHub.
- **Idle keepalive** → send `" "` instead of full OMTTally XML. Lighter payload, matches GitHub.

### 3. Kept (audio fix, not reverted)
- Audio header: ActiveChannels (0x03) at offset 16 — required for vMix audio.
- Audio routing: send only to video connections for OMT multiplexing.
