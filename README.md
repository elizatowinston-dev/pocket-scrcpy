# Pocket Scrcpy (Android-to-Android Client)

> **Notice:** This project is an independent open-source educational project and is **not affiliated with or endorsed by Genymobile** or the upstream [scrcpy](https://github.com/Genymobile/scrcpy) project.

An Android-to-Android scrcpy client running natively on your viewer device (**Google Pixel 4a 5G**) to mirror and control a target device (**Sony Xperia 5 IV**) with a broken or unusable screen. No laptop or intermediate PC required once installed!

```
+--------------------------+                         +--------------------------+
|  Viewer (Pixel 4a 5G)    |                         |  Target (Xperia 5 IV)    |
|                          |                         |                          |
|  [Jetpack Compose UI]    |    Wi-Fi / Hotspot      |  [scrcpy-server 5.0.1]   |
|  [Hardware MediaCodec]   |<=======================>|  [app_process]           |
|  [Native Kotlin ADB]     |   or USB-C to USB-C     |  [SurfaceControl mirror] |
+--------------------------+                         +--------------------------+
```

---

## Feature Implementation Status

Features below strictly separate what has JVM unit test coverage from what still requires live testing on physical phones:

### Unit-Tested (Pure JVM)
- **Wire Protocol Framing & Messages (`:protocol`)**:
  - `v5.0.1` dummy byte, 64-byte device meta, codec IDs (`h264`, `h265`, `av1` `0x00617631`, `vp8`, `vp9`), 12-byte MSB-1 session packets (initial & resize without payload consumption), and MSB-0 media packets (`ProtocolTest`, `StreamDemuxerTest`).
  - Control/device message serialization and parsing (`ProtocolTest`), plus audio codec header and disable/error codes (`AudioStreamHeaderTest`).
- **Aspect-Ratio & Multi-Touch Mapping (`:protocol`)**:
  - Letterbox/pillarbox scaling from viewer bounds to target video resolution, dispatching all active pointers on `ACTION_MOVE`, releasing all pointers on `ACTION_CANCEL`, and ignoring unrelated actions (`TouchMapperTest`).
- **Native Kotlin ADB Engine (`:adb`)**:
  - Packet framing, RSA signing/blob formatting, persistent RSA keypair storage/reload (`loadOrGenerateKeyPair`), stream multiplexing, and `sync:` server push (`AdbMessageTest`, `AdbClientTest`, `AdbSyncTest`).
- **USB Timeout & Server Startup Logic (`:app` JVM helpers)**:
  - Distinguishing USB `bulkTransfer` `-1` idle timeout from real I/O errors/disconnects (`UsbBulkTransferHelperTest`).
  - 5-second `localabstract:scrcpy_<scid>` retry loop and early-exit server shell output surfacing (`ServerStartupHelperTest`).

### Needs Hardware Test (Unverified on Physical Phones)
- **Direct Android-to-Android Video Streaming**: Stream video over Wi-Fi / hotspot to port 5555 (`unverified on hardware`).
- **Hardware Low-Latency Decoding**: `MediaCodec` decoding to `SurfaceView` with `KEY_LOW_LATENCY = 1` (`unverified on hardware`).
- **Multi-Touch Injection on Target**: Live pointer events delivered to the Xperia screen (`unverified on hardware`).
- **Automatic Reconnection**: Backoff auto-reconnect strategy if network packets drop or server disconnects (`unverified on hardware`).
- **USB-C OTG `adb tcpip 5555` Setup**: One-time USB host mode permission (`PendingIntent`), `USB_DEVICE_ATTACHED` filter, and `adb tcpip 5555` before switching to Wi-Fi or hotspot (`unverified on hardware`).
- **Broken Screen & Power Toggles**:
  - `SET_DISPLAY_POWER(false)` toggle to keep the damaged Xperia screen dark during mirroring (`unverified on hardware`).
  - Wake & Power button helper (`SET_DISPLAY_POWER(true)` + `KEYCODE_POWER`) (`unverified on hardware`).
- **Live Audio Forwarding**: Real-time audio playback via `AudioTrack` receiving 48kHz 16-bit raw stereo PCM (`audio_codec=raw`) (`unverified on hardware`).
- **Two-Way Clipboard Synchronization**: Clipboard exchange between viewer and target (`unverified on hardware`).
- **Foreground Service**: Ongoing notification to keep the session alive (`unverified on hardware`).
- **Pixel 4a 5G Specifics**: Portrait/landscape rotation support, edge-to-edge window inset handling, and immersive fullscreen mode (`unverified on hardware`).

---

## Downloading Prebuilt APKs from CI

Every push and pull request runs GitHub Actions (`.github/workflows/ci.yml`), which executes the unit tests and builds both `app-debug.apk` and `app-debugSigned.apk`.

### Option A: Web Browser (GitHub UI)
1. Open the repository on GitHub and click the **Actions** tab.
2. Select the latest passing **Android CI** workflow run for your branch or PR.
3. Scroll down to the **Artifacts** section.
4. Download **`app-debugSigned-apk`** (minified, R8-shrunk, signed with the debug keystore — recommended for phone installation) or **`app-debug-apk`**.
5. Unzip the downloaded `.zip` archive to extract `app-debugSigned.apk` (or `app-debug.apk`).
6. Transfer the `.apk` to your Pixel 4a 5G (or install via `adb install -r app-debugSigned.apk`) and allow installation from unknown sources if prompted.

### Option B: GitHub CLI (`gh`)
```bash
# List recent CI runs and grab the run ID
gh run list --workflow ci.yml --limit 5

# Download both APK artifacts from a run
gh run download <run-id> -n app-debugSigned-apk -n app-debug-apk
```

---

## First-Test Checklist (Testing on Real Phones)

Follow this sequence when testing between the Pixel 4a 5G (Viewer) and Sony Xperia 5 IV (Target):

1. **Physical Cable & USB Detection**: Connect Pixel 4a 5G and Xperia 5 IV using a USB-C to USB-C cable (set Xperia USB mode to File Transfer or Charging if needed). Confirm the Pixel launches/notifies for the attached USB ADB device.
2. **USB OTG `tcpip:5555` Execution**: Tap **USB OTG Setup (adb tcpip 5555)**, approve the Android USB Host permission dialog on the Pixel, and accept **"Allow USB debugging"** on the Xperia when prompted. Confirm `tcpip:5555` reports success.
3. **Wi-Fi / Hotspot Connection**: Unplug the USB-C cable, connect both devices to the same Wi-Fi network or mobile hotspot, and enter the Xperia's IP address and port `5555`.
4. **ADB `sync:` Server Push**: Tap **Connect to Xperia** and verify `assets/scrcpy-server.jar` is pushed to `/data/local/tmp/scrcpy-server.jar`.
5. **Server Launch & Handshake**: Verify `app_process` starts `scrcpy-server` v5.0.1 and completes the dummy byte, 64-byte device name, codec ID, and initial 12-byte session packet handshake.
6. **Video Stream & MediaCodec**: Confirm the H.264 stream renders smoothly onto the Pixel's `SurfaceView` without green frames or hangs on rotation.
7. **Touch & Multi-Touch Scaling**: Tap, drag, and pinch-to-zoom across the Pixel display; verify multi-touch coordinates register accurately on the Xperia.
8. **Broken Screen Dark Mode**: Tap the **Screen Off** button (lock icon); confirm the Xperia screen goes black while the mirror on the Pixel stays active.
9. **Hotspot Topologies & Live Audio**:
   - Test **Pixel Hotspot** or **Xperia Hotspot** and connect via assigned IP on port `5555`.
   - Toggle **Audio** (`audio_codec=raw`) and verify 48kHz PCM audio plays through the Pixel speaker.

---

## Pinned Upstream Version

- **Server Version**: `scrcpy-server` **v5.0.1**
- **Server SHA-256**: `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`
- **Minimum SDK**: Android 11 (API 30) on Viewer; Android 11+ on Target.

---

## Building from Source

Requirements:
- JDK 21
- Android SDK Build-Tools 35.0.0 / Platform API 35

```bash
# Clone the repository
git clone https://github.com/elizatowinston-dev/pocket-scrcpy.git
cd pocket-scrcpy

# Verify bundled scrcpy-server binary (or re-fetch from upstream)
./scripts/fetch_server.sh

# Run pure-JVM unit tests (33 tests)
./gradlew :protocol:test :adb:test

# Run Android module unit tests (5 tests)
./gradlew :app:testDebugUnitTest

# Build APKs
./gradlew :app:assembleDebug :app:assembleDebugSigned
```
By default (`build.gradle.kts`), Gradle writes outputs to `/tmp/build/app/outputs/apk/debug/app-debug.apk` and `/tmp/build/app/outputs/apk/debugSigned/app-debugSigned.apk` (override with `-PbuildRoot=<dir>`).

---

## License

This project is licensed under the Apache License 2.0 - see [LICENSE](LICENSE) for details.
Upstream scrcpy is Copyright (C) 2018 Genymobile and licensed under Apache-2.0.
