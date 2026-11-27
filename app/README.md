# Doorbell Companion

Fast incoming doorbell calls for Home Assistant on Android and iOS, with pre-warmed WebRTC live video and two-way audio when supported by the camera.

For installation, compatibility, and setup, see the [project README](../README.md).

## Shared behavior

1. **Zero-typing Home Assistant onboarding:** Discovers Home Assistant over Wi-Fi and signs in through the browser using OAuth with PKCE (`/android` and `/ios` client IDs returning to `doorbell-companion://auth-callback`).
2. **Doorbell discovery & warmup:** Pairs `camera.*` entities with press `binary_sensor.*` / `event.*` entities on the same device (matching the `doorbell` device class or translation key, an `_doorbell` entity ID suffix, or a Reolink **Visitor** sensor). Disables `preload_stream` so cameras such as UniFi Protect flush an immediate IDR keyframe on play, caches WebRTC ICE configuration, and refreshes expired OAuth tokens over the first reachable Home Assistant connection.
3. **Pre-warmed sub-second WebRTC:** Races local LAN and external Home Assistant WebSockets in parallel with local SDP offer generation while the phone is still ringing, showing a snapshot immediately and switching to WebRTC at the first frame.
4. **Two-way audio:** Negotiates send/receive audio upfront on supported cameras while keeping the microphone passive during ringing, then attaches the microphone as soon as you answer.

## Android

- A high-priority FCM push hits `RingService`, starting the Home Assistant connection and WebRTC offer at `t = 0 ms`—before Android finishes opening `CallActivity` full-screen on the lock screen.
- Answering enables hardware echo-cancelled speakerphone and attaches the microphone when the camera's SDP answer accepts backchannel audio.
- For call startup diagnostics, run `adb logcat -s DoorbellTiming:I DoorbellCompanion:I`.

## iOS

- PushKit (`DoorbellCalls`) receives the APNs VoIP push and reports the incoming call to CallKit while `CameraCall` fetches the snapshot and starts the WebRTC session.
- Answering activates the CallKit audio session and opens the live video view in Doorbell Companion.
- For call startup diagnostics, filter the Xcode console for `Doorbell Companion`.
