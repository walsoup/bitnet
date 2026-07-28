# Project Transition Plan & Future Scope

## What Didn't Work
1. **Peer Discovery Issue:**
   - Peers are not being detected by the scanner/advertiser layer.
   - We need to verify if the BLE Service UUIDs, advertising settings, packet serialization (`MeshPeerAnnouncement`), or permissions (e.g., location/scanning runtime permissions, background scanning limitations) are blocking discovery.

## New Architectural Direction (BitChat Clone + Internet Sharing Add-on)
1. **Model after BitChat-Android:**
   - Fork/reference the official Android repository structure: [bitchat-android](https://github.com/permissionlesstech/bitchat-android).
   - Core mesh functionality should be oriented around **mesh messaging (text chat)** first.
   - Peers should be able to send, receive, and route text messages natively through the Bluetooth mesh network.
   
2. **Internet Sharing as an Add-on Feature:**
   - Rather than the app being purely a VPN proxy tool, it should be a mesh messaging client where users can additionally toggle "Opt-in to Share Internet".
   - Peers can then discover who is sharing and route traffic through them as a premium/add-on feature alongside the primary messaging interface.

## Suggested Next Steps (Clean Slate / New Context)
1. **Bleed out context bloat:** Start the next session with a fresh context.
2. **Examine BitChat-Android codebase:** Analyze how it implements Bluetooth mesh messaging, peer discovery, and transport layers.
3. **Bridge BitChat mesh and BlueNet tunnel:** Integrate the existing L2CAP + StreamMultiplexer + VPN Tunnel proxying engine into the BitChat-style mesh architecture as a service overlay.
