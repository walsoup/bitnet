# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
BlueNet is an Android Bluetooth mesh network application inspired by BitChat. Devices auto-discover each other via BLE advertising/scanning, and any user can opt-in to share their internet connection with the mesh. Other users can then choose a sharer and route their traffic through them via a VPN tunnel over Bluetooth.

## High-Level Architecture
- **`com.bluenet.mesh`**: BLE mesh discovery layer. `MeshService` (foreground service) owns `MeshManager`, which orchestrates `MeshAdvertiser` (BLE advertising), `MeshScanner` (BLE scanning + peer tracking), and manages internet sharing/consumption lifecycle. `MeshPeer` is the data model, `MeshPeerAnnouncement` handles binary serialization for BLE payloads.
- **`com.bluenet.bluetooth`**: Handles low-level Bluetooth socket connections. Uses L2CAP Connection-Oriented Channels (CoC) with a fallback to RFCOMM High-Speed if L2CAP is unavailable.
- **`com.bluenet.multiplexer`**: A custom stream multiplexer protocol (`StreamMultiplexer`, `Frame`, `FrameType`) that allows concurrent network streams over a single Bluetooth socket.
- **`com.bluenet.host`**: Contains `HostService` and `HostProxyManager`. Implements the server-side proxy that receives multiplexed packets and forwards them to the real network.
- **`com.bluenet.client`**: Contains `BlueNetVpnService` and `TunPacketRouter`. Sets up a virtual network interface (TUN) to intercept device traffic and route it through the multiplexer to the sharer.
- **`com.bluenet.service`**: Quick Settings tile services for toggling internet sharing and mesh connections.
- **`MainActivity.kt`**: Unified mesh UI with peer list, "Share My Internet" toggle, and connection status card.

## Build and Development Commands
*Note: If the `gradlew` script is missing from the root directory, you may need to use a system-installed `gradle` or generate the wrapper using `gradle wrapper`.*

- **Build APK:** `./gradlew assembleDebug`
- **Run Linting:** `./gradlew lint`
- **Run Unit Tests:** `./gradlew testDebugUnitTest`
- **Run a specific test:** `./gradlew testDebugUnitTest --tests "com.bluenet.ExampleTest"`
- **Clean Project:** `./gradlew clean`

## Code Conventions and Notes
- Written in Kotlin, using `build.gradle.kts` for Gradle configuration.
- The project targets Android API 34 and has a minimum SDK of 29 (Android 10 required for L2CAP CoC APIs).
- Ensure required Bluetooth permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`) are handled properly for API 31+ (Android 12+).
- The Client mode depends heavily on `VpnService`, which requires user consent via standard Android VPN permission dialogues.
- BLE is used for mesh discovery (low power, always-on), while Classic Bluetooth (L2CAP/RFCOMM) is used for data-plane internet traffic (high bandwidth).