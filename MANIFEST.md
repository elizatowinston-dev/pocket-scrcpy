# Manifest of Project Files

This manifest summarizes the files in the `pocket-scrcpy` repository:

| Path | Description |
|---|---|
| `.github/workflows/ci.yml` | GitHub Actions workflow running unit tests and building `app-debug.apk` & `app-debugSigned.apk` on every push/PR |
| `app/src/main/assets/scrcpy-server.jar` | Official unmodified `scrcpy-server` v5.0.1 binary (`./scripts/fetch_server.sh`) |
| `protocol/` | Pure JVM protocol module (`ControlMessage`, `DeviceMessage`, `TouchMapper`, `StreamPacket`, `AudioStreamHeader`) + 26 unit tests |
| `adb/` | Pure JVM ADB module (`AdbClient`, `AdbStream`, `AdbSync`, `AdbCrypto`, `AdbMessage`) + 7 unit tests |
| `app/` | Android application module (Jetpack Compose UI, `SessionManager`, `MediaCodec` pipeline, `AdbUsbTransport`, `ServerStartupHelper`, `SessionService`) + 5 JVM unit tests |
| `docs/VERIFICATION.md` | Verification report, unit test inventory, and hardware-testing status |
| `README.md` | Project guide, unit-tested vs needs-hardware-test feature matrix, CI APK download instructions, and phone setup checklist |
| `PROTOCOL.md` | Binary wire protocol specification for `scrcpy` v5.0.1 |
| `DECISIONS.md` | Architectural decisions and design trade-offs |
| `THIRD_PARTY_LICENSES.md` | List of open-source dependencies, versions, and licenses |
| `SECURITY.md` | Responsible use, ADB key security notes, and vulnerability reporting |
| `CONTRIBUTING.md` | Developer onboarding and coding standards |
| `LICENSE` | Apache License 2.0 |
| `NOTICE` | Copyright notices and attribution for Genymobile |
| `scripts/fetch_server.sh` | Shell script to fetch and verify the official `scrcpy-server` v5.0.1 binary |
