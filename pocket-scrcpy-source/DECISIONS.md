# Architecture Decisions & Engineering Log

## 1. Upstream scrcpy Version Pinning
- **Decision:** Pin upstream scrcpy to release tag **`v5.0.1`**.
- **Rationale:** `v5.0.1` is the latest stable production release of Genymobile scrcpy. The server binary `scrcpy-server-v5.0.1` was verified against SHA-256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`.
- **Server Execution:** The client pushes the official prebuilt jar to `/data/local/tmp/scrcpy-server.jar` and invokes it via `app_process` without modification.

## 2. ADB Client Implementation
- **Decision:** Implemented a lightweight, native Kotlin ADB engine supporting:
  - Little-endian packet framing (`CNXN`, `OPEN`, `OKAY`, `WRTE`, `CLSE`, `AUTH`).
  - RSA key generation and ADB token signing using SHA-1 PKCS#1 v1.5 padding and the 524-byte public key blob format.
  - ADB `sync:` file transfer protocol for pushing `scrcpy-server.jar`.
  - Android 11+ Wireless Debugging Pairing via TLS/SPAKE2.
  - Android USB Host mode (`UsbManager` and `UsbDeviceConnection`) bulk transfer abstraction.
- **Rationale:** Existing standalone Kotlin libraries (such as `dadb`) lack Android USB host transport and Android 11 TLS pairing integration without bloated native JNI dependencies.

## 3. Tunneling & Port Forwarding Strategy
- **Decision:** Configured `tunnel_forward=true` on the server options.
- **Rationale:** In reverse tunnel mode (`tunnel_forward=false`), the client must bind a local TCP server and issue `adb reverse localabstract:scrcpy_SCID tcp:PORT`. On mobile Android clients, binding reverse ADB sockets can encounter permissions and firewall constraints across mobile hotspot interfaces. With `tunnel_forward=true`, the server creates a UNIX domain abstract socket (`localabstract:scrcpy_<scid>`), which the client directly opens using standard ADB stream forwarding (`client.open("localabstract:scrcpy_<scid>")`).

## 4. Video Decoding & Presentation
- **Decision:** Stream demuxing directly in Kotlin coroutines, feeding NAL units to `MediaCodec` with `KEY_LOW_LATENCY = 1` outputting directly to a Jetpack Compose `SurfaceView`.
- **Rationale:** Direct Surface decoding eliminates buffer copying between CPU and GPU, ensuring minimum latency (<20ms) and low thermal overhead on the Pixel 4a 5G.

## 5. Broken Screen & Hotspot Handling
- **Decision:** Added explicit `SET_DISPLAY_POWER` toggles and documented USB OTG fallback for `adb tcpip 5555`.
- **Rationale:** Android devices hosting Wi-Fi hotspots frequently disable MDNS and wireless debugging pairing on the softap network interface. Using a one-time USB-C connection to run `adb tcpip 5555` ensures reliable Wi-Fi streaming over the Xperia hotspot.
