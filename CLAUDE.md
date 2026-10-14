# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview
BlueNet is an Android Bluetooth mesh app inspired by BitChat. Phones discover each other over BLE, open encrypted L2CAP CoC links, relay signed chat messages across up to 7 hops, and can share internet: a client in direct range routes its IPv4 traffic through a sharer via `VpnService`.

## Architecture
Two layers, split by package:

- **`com.bluenet.core`** is pure Kotlin with no Android imports, so everything in it runs in JVM unit tests. Protocol logic belongs here.
  - `crypto`: P-256 `Identity` (peer ID = first 8 bytes of SHA-256 of the public key), `Handshake` (ephemeral + static ECDH, HKDF, key confirmation), `RecordCipher` (AES-GCM, counter nonce).
  - `link`: `SecureLink` wraps any byte stream. Channels: CONTROL (ping/goodbye/confirm), MESH, TUNNEL_UP (client→sharer), TUNNEL_DOWN (sharer→client).
  - `mesh`: signed `MeshPacket` v2 (TTL and hop count are outside the signature), flooding `MeshRouter`, `MessageStore`.
  - `tunnel`: `TunnelFrame` streams with credit flow control, IPv4/TCP/UDP codecs, `TunnelClient` (userspace TCP endpoint behind the TUN), `TunnelHost` (real sockets on the sharer, `DestinationPolicy.PUBLIC_INTERNET_ONLY`).
- **Android layer** wires the core to the platform:
  - `ble`: `BleAnnouncement` (scan-response manufacturer data) and `BleDiscovery` (advertiser + scanner).
  - `link`: `LinkManager` (L2CAP server and dialing, one link per peer, channel dispatch, per-link `TunnelHost`).
  - `node`: `MeshNode` is the composition root; `MeshRuntime` holds the single instance plus `TunnelStatus` for services, tiles and UI.
  - `service`: `MeshService` (foreground, `connectedDevice`), `BlueNetVpnService`, Quick Settings tiles.
  - `ui`: `MainActivity`/`MainScreen` and `ChatActivity`/`ChatScreen` in Jetpack Compose. Activities collect `MeshNode` flows into a UI state class; screens are stateless composables.
- **`design`** (module `:design`, package `com.walsoup.linen`) is the Linen design system: tokens, `LinenTheme` and components. `design/AGENTS.md` is its guide.

## Invariants
- **Threading.** `TunnelClient` state is confined to its single-threaded executor. `SecureLink.Listener` callbacks run on the link's reader thread and must not block or throw. `BleDiscovery` runs on the main thread; `MeshNode` setters may be called from any thread.
- **Wire formats.** Peers running older builds must reject a changed format rather than misparse it. `MeshPacket` and `BleAnnouncement` carry their own `VERSION`; everything spoken over a link (`SecureLink` records, control messages, `TunnelFrame`) is covered by `Handshake.VERSION`. Bump the matching one with any layout change.
- **Tunnels are single-hop.** A client only tunnels through a sharer in direct BLE range; mesh relaying carries chat, not traffic.
- **IPv6** stays unconfigured on the VPN so Android blocks it instead of leaking it.
- **UI** is built with the Linen design system; follow `design/AGENTS.md`. Colors, type and spacing come from `Linen.colors`, `Linen.type` and `LinenDimens` (`LinenPalette` outside Compose). No hex colors or color schemes under `app/` apart from the launcher icon. Every screen must work in light and dark.

## Build and Development Commands
- **Build APK:** `./gradlew assembleDebug`
- **Unit tests:** `./gradlew testDebugUnitTest` (one class: `--tests "com.bluenet.core.tunnel.TunnelClientTest"`)
- **Lint:** `./gradlew lint`
- `gradle.properties` pins `android.aapt2FromMavenOverride` to a Termux path for on-device builds; elsewhere pass `-Pandroid.aapt2FromMavenOverride=` (empty, as CI does) or point it at a local SDK `aapt2`.

## Code Conventions and Notes
- Kotlin 2.2 with `build.gradle.kts` and the version catalog in `gradle/libs.versions.toml`. AGP 8.7, Java 21. minSdk 29 (L2CAP CoC), targetSdk 34, compileSdk 35 (`:design` uses 36).
- The root `build.gradle.kts` pins some transitive AndroidX, Kotlin and coroutines versions to ones in the local Gradle cache so the project builds with `--offline`. Raise them freely when online.
- Runtime permissions live in `Permissions`: Android 12+ needs `BLUETOOTH_SCAN`/`CONNECT`/`ADVERTISE`; Android 10–11 need fine location for BLE scans.
- The VPN needs user consent from `VpnService.prepare`; the tiles open `MainActivity` when consent or permissions are missing.
