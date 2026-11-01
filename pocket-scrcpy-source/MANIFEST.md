# Manifest of Project Deliverables

This manifest details the essential files, documentation, and build artifacts for the `scrcpy-android` project:

| Path | Size | Description / Generating Command |
|---|---|---|
| `dist/scrcpy-android-debug.apk` | 64 MB | Full debug APK build (`./gradlew :app:assembleDebug`) |
| `dist/scrcpy-android-release-debugsigned.apk` | 16 MB | Minified and shrunk APK signed with debug key (`./gradlew :app:assembleDebugSigned`) |
| `dist/SHA256SUMS.txt` | < 1 KB | SHA-256 checksums of APKs and server binary |
| `dist/BUILD_INFO.txt` | < 1 KB | Build environment manifest, toolchain versions, and execution log |
| `app/src/main/assets/scrcpy-server.jar` | 734 KB | Official scrcpy-server v5.0.1 binary (`./scripts/fetch_server.sh`) |
| `protocol/` | ~30 KB | Pure JVM protocol module (ControlMessage, DeviceMessage, TouchMapper, StreamPacket, AudioStreamHeader) |
| `adb/` | ~50 KB | Pure Kotlin ADB module (AdbClient, AdbSync, AdbCrypto, Spake25519, PairingCipher, AdbPairingClient) |
| `app/` | ~800 KB | Android application module (Jetpack Compose UI, MediaCodec pipeline, UsbTransport, Foreground Service) |
| `docs/VERIFICATION.md` | ~4 KB | Detailed verification report, exact unit test counts, and aapt inspection output |
| `README.md` | ~6 KB | Complete project guide, architecture diagram, 10-step first-test checklist, and setup instructions |
| `PROTOCOL.md` | ~5 KB | Binary wire protocol specification for scrcpy v5.0.1 |
| `DECISIONS.md` | ~3 KB | Architectural decisions and design trade-offs |
| `THIRD_PARTY_LICENSES.md` | ~2 KB | Comprehensive list of open source dependencies, versions, and licenses |
| `SECURITY.md` | ~1 KB | Responsible use, ADB security disclosure, and vulnerability reporting |
| `CONTRIBUTING.md` | ~1 KB | Developer onboarding and coding standards |
| `LICENSE` | ~11 KB | Apache License 2.0 |
| `NOTICE` | < 1 KB | Copyright notices and attribution for Genymobile and Bouncy Castle |
| `.github/workflows/ci.yml` | ~1 KB | GitHub Actions workflow building and testing on every commit/PR |
| `.github/ISSUE_TEMPLATE/` | ~2 KB | Issue templates for bug reports and feature requests |
| `scripts/fetch_server.sh` | ~1 KB | Shell script to fetch and verify official scrcpy-server v5.0.1 binary |
