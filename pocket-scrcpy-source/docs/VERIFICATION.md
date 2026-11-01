# Verification & Test Report: Scrcpy for Android

## 1. Environment & Build Configuration
- **Operating System:** Linux x86_64
- **Toolchain:** OpenJDK 21.0.13, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Jetpack Compose BOM 2024.10.01
- **Target Platform:** Android SDK 35 (`compileSdk` / `targetSdk`), `minSdk` 30 (Android 11)
- **Pinned Upstream Binary:** `scrcpy-server` v5.0.1 (SHA-256: `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`)

---

## 2. Automated Test Results (Unit Tests)

All automated unit tests pass 100% on pure JVM across both `:protocol` and `:adb` modules.

Command executed:
```bash
./gradlew :protocol:test :adb:test --no-daemon
```

### Exact Test Counts by Test Class:
- **`com.scrcpy.protocol.ProtocolTest`** (9 tests, 0 failures, 0 errors)
  - `testInjectKeycode`
  - `testInjectText`
  - `testInjectTouchEvent`
  - `testBackOrScreenOn`
  - `testSetClipboard`
  - `testSetDisplayPower`
  - `testDeviceMessageClipboard`
  - `testDeviceMessageAckClipboard`
  - `testStreamPacketParsing`
- **`com.scrcpy.protocol.TouchMapperTest`** (4 tests, 0 failures, 0 errors)
  - `testExactAspectMatch`
  - `testLetterboxMapping`
  - `testPillarboxMapping`
  - `testInvalidDimensions`
- **`com.scrcpy.protocol.StreamDemuxerTest`** (3 tests, 0 failures, 0 errors)
  - `testDeviceMetaParsing`
  - `testCodecIdParsing`
  - `testConfigAndKeyframePackets`
- **`com.scrcpy.protocol.AudioStreamHeaderTest`** (3 tests, 0 failures, 0 errors)
  - `testRawCodecHeader`
  - `testAudioStreamDisabledExplicit`
  - `testAudioStreamConfigError`
- **`com.scrcpy.adb.AdbMessageTest`** (3 tests, 0 failures, 0 errors)
  - `testAdbMessageSerialization`
  - `testAdbCryptoBlobGeneration`
  - `testAdbSignature`
- **`com.scrcpy.adb.AdbClientTest`** (2 tests, 0 failures, 0 errors)
  - `testAdbConnectAndShellStream`
  - `testAdbStreamRefusedSurfacesError`
- **`com.scrcpy.adb.AdbSyncTest`** (1 test, 0 failures, 0 errors)
  - `testSyncSendFileProtocol`
- **`com.scrcpy.adb.Spake25519Test`** (4 tests, 0 failures, 0 errors)
  - `testSpake2KeyAgreement`
  - `testPairingCipherAes128Gcm`
  - `testFullPairingProtocolSuccess`
  - `testPairingFailsWithWrongPairingCode`

**Total: 29 tests executed, 29 passed, 0 failed.**

---

## 3. APK Inspection & Verification

### Assets Verification
Both built APKs contain the official unmodified `scrcpy-server.jar`:
```
SHA-256: 764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536 assets/scrcpy-server.jar
```

### `aapt dump badging` Output:
```
package: name='com.scrcpy.client.debug' versionCode='1' versionName='1.0.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
sdkVersion:'30'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
application-label:'Scrcpy Android'
```

---

## 4. Hardware Testing Status & Limitations Disclosure

- **What was verified:**
  - Automated compilation, lint checks, and JVM unit test suites against verified byte fixtures.
  - SPAKE2 key agreement and full pairing state machine tested against loopback threads.
  - Touch aspect-ratio letterbox/pillarbox math verified across portrait and landscape resolutions.
  - Sync push protocol verified against loopback ADB server.
  - Audio header decoding, raw PCM stream configuration, and disablement codes.
- **What could NOT be verified on real hardware:**
  - **No physical phone was connected in the build environment.** Full end-to-end performance on the Google Pixel 4a 5G and Sony Xperia 5 IV was not tested on physical silicon.
  - Physical USB-C OTG hardware connection and kernel Wi-Fi hotspot behavior depend on physical hardware execution.
