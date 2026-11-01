# Contributing to Scrcpy for Android

Thank you for your interest in contributing to `scrcpy-android`!

## Code Guidelines
- Codebase is written in Kotlin using Jetpack Compose for UI.
- All wire protocol serialization and parsing must include unit tests against exact upstream byte fixtures.
- Keep dependencies lean and avoid bundling large binary dependencies into the repository.
- Follow Android architecture recommendations (StateFlow, Coroutines, Service lifecycle).

## Development Setup
1. Clone repository and run `./scripts/fetch_server.sh`.
2. Open in Android Studio Hedgehog or newer (or use command line `./gradlew assembleDebug`).
3. Ensure all tests pass: `./gradlew test`.
