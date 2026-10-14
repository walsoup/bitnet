# BlueNet (bitnet)

<div align="center">

[![Status: Unfinished / Work in Progress](https://img.shields.io/badge/STATUS-UNFINISHED%20%2F%20WORK%20IN%20PROGRESS-critical?style=for-the-badge&logo=git&logoColor=white)](https://github.com/walsoup/bitnet)

</div>

> [!CAUTION]
> ### 🚧 UNFINISHED / WORK IN PROGRESS
> BlueNet is under active development. The 2.0 rewrite is covered by JVM unit tests but has not yet been tested on real devices. Wire formats may still change. Not recommended for daily use yet.

> Android Bluetooth mesh chat and internet sharing without root.

BlueNet links nearby Android phones over Bluetooth Low Energy. Phones find each other with BLE advertising, open encrypted L2CAP channels to each other, and relay signed chat messages across up to 7 hops. Any phone with Wi-Fi or mobile data can opt in to share it, and a phone in direct range can then route its traffic through that sharer with a local VPN.

---

## How It Works

```
[ Client phone ]                                         [ Sharer phone ]
   │                                                          │
   ├─ VpnService TUN (IPv4)                                   │
   ├─ TunnelClient: small userspace TCP/UDP endpoint          │
   │    one tunnel stream per flow, credit flow control       │
   │                                                          │
   └──── SecureLink (ECDH + AES-GCM) over BLE L2CAP CoC ─────►│
                                                              ├─ TunnelHost: real sockets,
                                                              │  public destinations only
                                                              └─ Internet (Wi-Fi / mobile)
```

1. **Discovery.** Every phone advertises a BLE service UUID. Its scan response carries a small announcement: peer ID, a sharing flag, the L2CAP PSM to connect to, and a short name.
2. **Links.** Phones open L2CAP connection-oriented channels to each other (Android 10+). Every link starts with a mutually authenticated handshake, and records are then encrypted with AES-256-GCM. Each phone keeps up to four links to nearby peers so the mesh stays connected.
3. **Mesh messages.** Chat messages and presence announcements are signed with the sender's key. They are flooded across links with duplicate suppression and a 7-hop limit. Direct messages are acknowledged so the sender sees "Delivered".
4. **Internet sharing.** The client's VPN terminates each app's TCP connection locally and opens a matching stream to the sharer. The sharer only answers SYNs after the real connection succeeds, and it applies per-stream credit so a fast server can't overrun a slow Bluetooth link. UDP (including DNS) is relayed as datagrams.

### Security model

- A peer ID is a hash of the peer's P-256 public key, so it can't be claimed without the matching private key.
- The link handshake authenticates both ends (ephemeral and static ECDH, then key confirmation). A client checks that the peer it reached is the peer it meant to dial.
- Mesh packets are signed end to end. Relays can't forge or alter them, but they can read them: chat is **not** end-to-end encrypted.
- The key is generated on the phone and app backups are disabled.
- A sharer only connects to public internet addresses. Private, loopback, link-local, CGNAT, multicast and reserved ranges are refused, so clients can't reach the sharer's LAN.
- The VPN only configures IPv4, which makes Android block IPv6 rather than let it bypass the tunnel.

---

## Features

- **No root.** Built on standard Android APIs: `VpnService`, BLE advertising and scanning, and L2CAP CoC sockets.
- **Mesh chat.** Direct and broadcast messages that hop between phones, with delivery receipts.
- **Internet sharing.** One switch to share; one tap on a nearby sharer to use it. Links that drop are re-established automatically.
- **Quick Settings tiles.** Toggle sharing, or reconnect to the last sharer, from the notification shade.
- **Calm UI.** Jetpack Compose screens built on the Linen design system, in light and dark.

---

## Requirements

- **Android 10+ (API 29)** for L2CAP connection-oriented channels.
- **Bluetooth permissions.** Android 12+ asks for Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`). Android 10 and 11 ask for location, which BLE scanning requires there.
- **VPN consent** the first time you use someone's internet.

---

## Building from Source

```bash
git clone https://github.com/walsoup/bitnet.git
cd bitnet

./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # JVM tests for the protocol core
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

---

## Project Structure

```
app/src/main/java/com/bluenet/
├── core/             # Pure Kotlin, no Android imports; unit-tested on the JVM
│   ├── crypto/       # Identity keys, link handshake, record cipher
│   ├── link/         # SecureLink: encrypted, multiplexed link over any byte stream
│   ├── mesh/         # Signed mesh packets, flooding router, message store
│   └── tunnel/       # Tunnel frames, IPv4/TCP/UDP codecs, TunnelClient, TunnelHost
├── ble/              # BLE announcement format, advertiser and scanner
├── link/             # LinkManager: L2CAP sockets, one link per peer, channel dispatch
├── node/             # MeshNode composition root, settings, peer directory
├── service/          # Mesh foreground service, VPN service, Quick Settings tiles
└── ui/               # Main screen and chat (Jetpack Compose)

design/               # Linen design system (Compose tokens, theme, components)
```

---

## License

This project is licensed under the [MIT License](LICENSE).
