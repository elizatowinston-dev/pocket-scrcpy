# Verification & Test Report: Pocket Scrcpy

## 1. Environment & Build Configuration
- **Toolchain Configured:** JDK 21, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Jetpack Compose BOM 2024.10.01
- **Target Platform:** Android SDK 35 (`compileSdk` / `targetSdk`), `minSdk` 30 (Android 11)
- **Pinned Upstream Binary:** `scrcpy-server` v5.0.1 (SHA-256: `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`)
- **CI Workflow:** `.github/workflows/ci.yml` executes `:protocol:test`, `:adb:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugSigned` on every push and PR.

---

## 2. Unit Test Suite Inventory (38 Tests Across 9 Classes)

Commands run in CI:
```bash
./gradlew :protocol:test :adb:test --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
```

### `:protocol` (26 tests across 4 classes)
- **`com.scrcpy.protocol.ProtocolTest`** (9 tests)
  - `testInjectKeycode`
  - `testInjectText`
  - `testInjectTouchEvent`
  - `testBackOrScreenOn`
  - `testSetClipboard`
  - `testSetDisplayPower`
  - `testDeviceMessageClipboard`
  - `testDeviceMessageAckClipboard`
  - `testStreamPacketParsing`
- **`com.scrcpy.protocol.TouchMapperTest`** (8 tests)
  - `testExactAspectMatch`
  - `testLetterboxMapping`
  - `testPillarboxMapping`
  - `testInvalidDimensions`
  - `testActionMoveSendsAllActivePointers`
  - `testPointerDownAndPointerUpDispatchActionIndexOnly`
  - `testActionCancelCancelsAndReleasesAllActivePointers`
  - `testAndroidMotionEventActionMappingAndIgnoredActions`
- **`com.scrcpy.protocol.StreamDemuxerTest`** (6 tests)
  - `testDeviceMetaParsing`
  - `testUpstreamVideoCodecIds`
  - `testUpstreamSessionPacketFixture12BytesNoPayload`
  - `testUpstreamFullVideoHandshakeAndFramesFixture`
  - `testZeroLengthMediaPacketThrows`
  - `testZeroDimensionSessionPacketThrows`
- **`com.scrcpy.protocol.AudioStreamHeaderTest`** (3 tests)
  - `testRawCodecHeader`
  - `testAudioStreamDisabledExplicit`
  - `testAudioStreamConfigError`

### `:adb` (7 tests across 3 classes)
- **`com.scrcpy.adb.AdbMessageTest`** (4 tests)
  - `testAdbMessageSerialization`
  - `testAdbCryptoBlobGeneration`
  - `testAdbSignature`
  - `testLoadOrGenerateKeyPairPersistsSameKey`
- **`com.scrcpy.adb.AdbClientTest`** (2 tests)
  - `testAdbConnectAndShellStream`
  - `testAdbStreamRefusedSurfacesError`
- **`com.scrcpy.adb.AdbSyncTest`** (1 test)
  - `testSyncSendFileProtocol`

### `:app` (5 tests across 2 classes)
- **`com.scrcpy.client.usb.UsbBulkTransferHelperTest`** (1 test)
  - `testTimeoutIsDistinguishedFromRealIoError`
- **`com.scrcpy.client.ServerStartupHelperTest`** (4 tests)
  - `testOpenWithRetrySucceedsAfterTransientFailures`
  - `testEarlyServerExitAbortsRetryAndSurfacesShellOutput`
  - `testEarlyExitMidRetryLoopSurfacesShellOutput`
  - `testTimeoutAfterFiveSecondsIncludesDetails`

---

## 3. Server Asset Verification

`app/src/main/assets/scrcpy-server.jar` was verified locally with `sha256sum`:
```
764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536  app/src/main/assets/scrcpy-server.jar
```

---

## 4. Hardware Testing Status & Limitations Disclosure

- **What was audited against upstream `v5.0.1` & covered by JVM tests:**
  - `scrcpy-server` `v5.0.1` options (`send_stream_meta`, `send_dummy_byte`, `send_device_meta`, `send_frame_meta`), `DesktopConnection.open()` socket accept ordering, 12-byte session vs media packet framing, and codec FourCC IDs verified directly against `Genymobile/scrcpy` `v5.0.1` sources.
  - Persistent ADB RSA key generation/reloading, USB `bulkTransfer` timeout classification, multi-touch coordinate mapping / `ACTION_MOVE` / `ACTION_CANCEL` pointer cleanup, and server socket retry / early-exit shell error formatting.
- **What is unverified on hardware:**
  - **No physical phone was connected during development (`unverified on hardware`).** Live behavior on the Google Pixel 4a 5G and Sony Xperia 5 IV — including USB-C OTG `PendingIntent` permission flow, `adbd tcpip 5555` restart, `MediaCodec` rendering to `SurfaceView`, `AudioTrack` PCM output, multi-touch injection, and Wi-Fi/hotspot resilience — requires real-device testing.
