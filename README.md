# Scrcpy for Android (Native Controller & Viewer)

> **Notice:** This project is an independent open-source educational project and is **not affiliated with or endorsed by Genymobile** or the upstream [scrcpy](https://github.com/Genymobile/scrcpy) project.

An Android-to-Android scrcpy client running natively on your viewer device (**Google Pixel 4a 5G**) to mirror and control a target device (**Sony Xperia 5 IV**) with a broken or unusable screen. No laptop or intermediate PC required!

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

Features below are separated into what has unit test coverage and what still requires physical hardware verification on the Pixel 4a 5G and Sony Xperia 5 IV:

### Unit-Tested (Pure JVM)
- **Wire Protocol Framing & Messages**: Control/device message serialization and parsing (`ProtocolTest`, `StreamDemuxerTest`, `AudioStreamHeaderTest`).
- **Aspect-Ratio Touch Coordinate Mapping**: Letterbox/pillarbox scaling from viewer bounds to target video resolution (`TouchMapperTest`).
- **Native Kotlin ADB Engine**: Packet framing, RSA signing/blob formatting, stream multiplexing, and `sync:` server push (`AdbMessageTest`, `AdbClientTest`, `AdbSyncTest`).

### Needs Hardware Test (Unverified on Physical Phones)
- **Direct Android-to-Android Video Streaming**: Stream video over Wi-Fi / hotspot to port 5555 (`unverified on hardware`).
- **Hardware Low-Latency Decoding**: `MediaCodec` decoding to `SurfaceView` with `KEY_LOW_LATENCY = 1` (`unverified on hardware`).
- **Multi-Touch Injection on Target**: Live pointer events delivered to the Xperia screen (`unverified on hardware`).
- **Automatic Reconnection**: Backoff auto-reconnect strategy if network packets drop or server disconnects (`unverified on hardware`).
- **USB-C OTG `adb tcpip 5555` Setup**: One-time USB host mode setup to enable TCP/IP ADB on port 5555 before switching to Wi-Fi or hotspot (`unverified on hardware`).
- **Broken Screen & Power Toggles**:
  - `SET_DISPLAY_POWER(false)` toggle to keep the damaged Xperia screen dark during mirroring (`unverified on hardware`).
  - Wake & Power button helper (`SET_DISPLAY_POWER(true)` + `KEYCODE_POWER`) (`unverified on hardware`).
- **Live Audio Forwarding**: Real-time audio playback via `AudioTrack` receiving 48kHz 16-bit raw stereo PCM (`audio_codec=raw`) (`unverified on hardware`).
- **Two-Way Clipboard Synchronization**: Clipboard exchange between viewer and target (`unverified on hardware`).
- **Foreground Service**: Ongoing notification to keep the session alive (`unverified on hardware`).
- **Pixel 4a 5G Specifics**: Portrait/landscape rotation support, edge-to-edge window inset handling, and immersive fullscreen mode (`unverified on hardware`).

---

## First-Test Checklist (Testing on Real Phones)

Follow this sequence when testing between the Pixel 4a 5G (Viewer) and Sony Xperia 5 IV (Target):

1. **Physical Cable & USB Detection**: Connect Pixel and Xperia using a USB-C to USB-C cable. Confirm the Pixel prompts for USB permission and detects the Xperia ADB interface.
2. **USB OTG `tcpip:5555` Execution**: Tap **USB OTG Setup** and accept "Allow USB debugging" on the Xperia when prompted. Verify that `tcpip:5555` succeeds.
3. **Wi-Fi / Hotspot Connection**: Unplug the USB-C cable, connect both devices to the same Wi-Fi network or hotspot, and enter the Xperia IP and port `5555`.
4. **ADB `sync:` Server Push**: Tap **Connect to Xperia** and verify `assets/scrcpy-server.jar` is pushed to `/data/local/tmp/scrcpy-server.jar`.
5. **Server Launch & Handshake**: Verify `app_process` starts `scrcpy-server` v5.0.1 and completes the dummy byte, 64-byte device name, codec ID, and initial session packet handshake.
6. **Video Stream & MediaCodec**: Confirm the H.264 stream renders smoothly onto the Pixel's `SurfaceView`.
7. **Touch & Multi-Touch Scaling**: Tap, drag, and pinch-to-zoom across the Pixel display; verify multi-touch coordinates register accurately on the Xperia.
8. **Broken Screen Dark Mode**: Tap the **Screen Off** button (lock icon); confirm the Xperia screen goes black while the mirror on the Pixel stays active.
9. **Hotspot Topologies & Live Audio**:
   - Test **Pixel Hotspot** or **Xperia Hotspot** and connect via assigned IP on port 5555.
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
git clone https://github.com/<your-username>/scrcpy-android.git
cd scrcpy-android

# Fetch and verify scrcpy-server binary
./scripts/fetch_server.sh

# Run all 25 unit tests
./gradlew :protocol:test :adb:test

# Build APKs
./gradlew :app:assembleDebug :app:assembleDebugSigned
```
The output APKs will be generated in `app/build/outputs/apk/` and copied to `dist/`.

---

## License

This project is licensed under the Apache License 2.0 - see [LICENSE](LICENSE) for details.
Upstream scrcpy is Copyright (C) 2018 Genymobile and licensed under Apache-2.0.
