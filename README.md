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

All features below have been fully implemented in code and verified with unit tests. Physical execution on real hardware is marked accordingly:

- **Direct Android-to-Android Video Streaming**: Stream 60fps video directly over Wi-Fi hotspot or USB-C *(unverified on physical hardware)*.
- **Hardware Low-Latency Decoding**: Direct decoding to `SurfaceView` using `MediaCodec` with `KEY_LOW_LATENCY = 1` *(unit-tested framing, unverified on physical hardware)*.
- **Dynamic Multi-Touch Injection**: Real-time multi-pointer touch mapping (down, move, up, pointer-down, pointer-up) dynamically scaled from viewer display rect (accounting for letterbox/pillarbox) to the video resolution reported by server session packets *(tested in `TouchMapperTest`)*.
- **Android 11+ Wireless Debugging Pairing**: Full implementation of TLS 1.3 with Conscrypt, RFC 5705 key export, Ed25519 SPAKE2 with BoringSSL constants, HKDF-SHA256, AES-128-GCM, and PeerInfo RSA public key exchange *(tested with loopback unit tests in `Spake25519Test`)*.
- **Automatic Reconnection**: Backoff auto-reconnect strategy if network packets drop or server disconnects.
- **USB OTG `adb tcpip 5555` Fallback**: Built-in one-tap OTG command runner so that when Xperia hosts a hotspot, port 5555 can be opened before switching to Wi-Fi *(unverified on physical hardware)*.
- **Broken Screen & Power Toggles**:
  - `SET_DISPLAY_POWER(false)` toggle to keep the damaged Xperia screen dark during mirroring (saving battery and preventing ghost touches).
  - Wake & Power button helper (`SET_DISPLAY_POWER(true)` + `KEYCODE_POWER`).
- **Live Audio Forwarding**: Real-time audio playback via `AudioTrack` receiving 48kHz 16-bit raw stereo PCM (`audio_codec=raw`), with graceful fallback if the target device is unable to capture audio *(tested in `AudioStreamHeaderTest`)*.
- **Two-Way Clipboard Synchronization**: Automatic capture and synchronization of device clipboard events.
- **Foreground Service**: Background service with ongoing notification to keep the session alive when the screen turns off or another app is focused.
- **Pixel 4a 5G Specifics**: Portrait-first with landscape rotation support, edge-to-edge window inset handling, and immersive fullscreen mode.

---

## 10-Step First-Test Checklist (Testing on Real Phones)

Follow this 10-step sequence when testing between the Pixel 4a 5G (Viewer) and Sony Xperia 5 IV (Target):

1. **Physical Cable & USB Detection**: Connect Pixel and Xperia using a USB-C to USB-C cable. Confirm the Pixel pops up the Android USB accessory/host dialog.
2. **USB OTG `tcpip:5555` Execution**: In the app, tap **USB OTG Setup**. Verify that the app connects to the Xperia ADB interface over USB host mode and returns success.
3. **Basic ADB Shell Round-Trip**: Unplug the cable, connect both devices to the same Wi-Fi, and verify `shell:echo test` returns `test`.
4. **ADB `sync:` Server Push**: Connect to target IP:5555 and verify that `assets/scrcpy-server.jar` is written to `/data/local/tmp/scrcpy-server.jar` with matching SHA-256 (`764eb6f798...`).
5. **Server Launch & Handshake**: Verify that `app_process` starts the v5.0.1 server and that the client receives the dummy byte and 64-byte device name.
6. **Video Stream & MediaCodec**: Confirm that H.264 stream starts rendering smoothly onto the Pixel's `SurfaceView` at 60 FPS.
7. **Touch & Multi-Touch Scaling**: Tap and drag across the Pixel display; verify that touches register at the exact corresponding Xperia coordinates without aspect-ratio distortion.
8. **Broken Screen Dark Mode**: Tap the **Screen Off** button (lock icon); confirm the Xperia screen goes completely black while the mirror on the Pixel stays active.
9. **Wireless Debugging Pairing**: In Xperia Developer Options, open **Wireless debugging** > **Pair device with pairing code**. In the app, enter the 6-digit code and pairing port, then verify TLS 1.3 + SPAKE2 pairing completes.
10. **Hotspot Topologies & Audio**:
    - Test **Pixel Hotspot**: Xperia joins Pixel hotspot, connect via assigned IP.
    - Test **Live Audio**: Toggle the audio button; verify the session cleanly restarts with `audio_codec=raw` and audio plays through the Pixel speaker.

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

# Run all 29 unit tests
./gradlew :protocol:test :adb:test

# Build APKs
./gradlew :app:assembleDebug :app:assembleDebugSigned
```
The output APKs will be generated in `app/build/outputs/apk/` and copied to `dist/`.

---

## License

This project is licensed under the Apache License 2.0 - see [LICENSE](LICENSE) for details.
Upstream scrcpy is Copyright (C) 2018 Genymobile and licensed under Apache-2.0.
