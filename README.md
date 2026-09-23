# BlueNet (bitnet)

<div align="center">

[![Status: Unfinished / Work in Progress](https://img.shields.io/badge/STATUS-UNFINISHED%20%2F%20WORK%20IN%20PROGRESS-critical?style=for-the-badge&logo=git&logoColor=white)](https://github.com/walsoup/bitnet)

</div>

> [!CAUTION]
> ### 🚧 UNFINISHED / WORK IN PROGRESS
> BlueNet is under active development and experimental testing. Protocol APIs, socket multiplexing framing, and packet routing are subject to change. Not recommended for daily driver use yet.

> Android Bluetooth mesh networking and internet sharing without root.

BlueNet turns Android devices into an ad-hoc local mesh over Bluetooth Low Energy (BLE) and classic Bluetooth (L2CAP / RFCOMM). When internet access is scarce or unavailable, any node connected to Wi-Fi or cellular can opt-in to share its connection. Neighboring nodes discover the sharer automatically and route outbound traffic through a local VPN tunnel.

---

## How It Works

```
[ Client Device ]                                  [ Sharer Device ]
   │                                                    │
   ├─► Local TUN Interface (VpnService)                 │
   │   Captures outbound IP packets                     │
   │                                                    │
   ├─► Stream Multiplexer Protocol                      │
   │   Multiplexes sockets over one L2CAP channel       │
   │                                                    │
   └─────────────── Bluetooth (L2CAP CoC / RFCOMM) ────►│
                                                        ├─► Host Proxy Service
                                                        │   Demuxes & forwards packets
                                                        │
                                                        └─► Internet (Wi-Fi / Cellular)
```

1. **Discovery**: Nodes broadcast and scan using BLE advertisements (`com.bluenet.mesh`), maintaining a live peer table with signal strength and sharing status.
2. **Data Plane**: Connections establish over Bluetooth L2CAP Connection-Oriented Channels (CoC) on Android 10+ (API 29+), falling back to RFCOMM High-Speed if L2CAP is unsupported.
3. **Multiplexing**: A custom binary frame protocol (`StreamMultiplexer`) handles multiple concurrent TCP/UDP streams over a single physical Bluetooth link.
4. **Transparent Routing**: The client sets up an Android `VpnService` virtual network interface, routing selected device traffic through the shared mesh link.

---

## Features

- **Zero Root Required**: Built entirely on standard Android APIs (`VpnService`, `BluetoothManager`, `ScanCallback`).
- **L2CAP CoC Sockets**: High-throughput Bluetooth transport on modern devices.
- **Battery-Conscious Discovery**: Low-duty-cycle BLE advertising for peer tracking, reserving high-power Bluetooth Classic exclusively for active data sessions.
- **Quick Settings Tile**: Toggle sharing or connect directly from Android notification shade.
- **Material 3 Interface**: Jetpack Compose UI showing live peer discovery, latency, and throughput counters.

---

## Requirements

- **Android 10+ (API level 29+)**: Required for L2CAP Connection-Oriented Channels.
- **Bluetooth Permissions**: Android 12+ requires `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE`.
- **VPN Consent**: Android prompts once to grant VPN tunneling authorization to the app.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/walsoup/bitnet.git
cd bitnet

# Build debug APK
./gradlew assembleDebug

# Run unit and protocol tests
./gradlew testDebugUnitTest
```

Debug APK output will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Project Structure

```
app/src/main/java/com/bluenet/
├── bluetooth/        # Low-level L2CAP and RFCOMM Bluetooth sockets
├── client/           # VpnService, TUN packet capture, and client router
├── host/             # Server-side proxy and packet forwarder
├── mesh/             # BLE advertiser, scanner, and peer discovery state
├── multiplexer/      # Binary framing protocol and stream demuxer
├── service/          # Android foreground services and Quick Settings tiles
└── ui/               # Jetpack Compose screens and status indicators
```

---

## License

This project is licensed under the [MIT License](LICENSE).
