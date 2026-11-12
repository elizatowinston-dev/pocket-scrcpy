# Third-Party Licenses and Dependencies

This project relies on the following open-source libraries and components:

### 1. Genymobile scrcpy-server
- **Artifact:** `scrcpy-server-v5.0.1` (embedded in `assets/scrcpy-server.jar`)
- **Version:** 5.0.1
- **Homepage:** https://github.com/Genymobile/scrcpy
- **License:** Apache License, Version 2.0
- **Copyright:** Copyright (C) 2018 Genymobile

### 2. Conscrypt Android
- **Coordinate:** `org.conscrypt:conscrypt-android`
- **Version:** 2.5.3
- **Homepage:** https://github.com/google/conscrypt
- **License:** Apache License, Version 2.0
- **Purpose:** RFC 5705 TLS key material exporter (`exportKeyingMaterial`) for Android 11+ Wireless Debugging Pairing.

### 3. Bouncy Castle Cryptography APIs
- **Coordinates:**
  - `org.bouncycastle:bcprov-jdk18on:1.78.1`
  - `org.bouncycastle:bcpkix-jdk18on:1.78.1`
- **Version:** 1.78.1
- **Homepage:** https://www.bouncycastle.org/
- **License:** Bouncy Castle Licence (MIT / X11 style)
- **Copyright:** Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc.
- **Purpose:** Self-signed X.509 certificate generation for TLS 1.3 mutual authentication during ADB wireless pairing.

### 4. Kotlin & Kotlin Coroutines
- **Coordinates:**
  - `org.jetbrains.kotlin:kotlin-stdlib:2.0.21`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`
- **License:** Apache License, Version 2.0
- **Copyright:** Copyright 2010-2024 JetBrains s.r.o.

### 5. AndroidX & Jetpack Compose
- **Coordinates:**
  - `androidx.core:core-ktx:1.15.0`
  - `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`
  - `androidx.activity:activity-compose:1.9.3`
  - `androidx.compose:compose-bom:2024.10.01`
  - `androidx.compose.ui:ui`
  - `androidx.compose.material3:material3`
- **License:** Apache License, Version 2.0
- **Copyright:** The Android Open Source Project

### 6. JUnit
- **Coordinate:** `junit:junit:4.13.2`
- **License:** Eclipse Public License 1.0
- **Purpose:** Test framework for JVM unit tests.
