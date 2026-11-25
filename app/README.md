# Doorbell Companion

Fast incoming doorbell calls for Home Assistant on Android and iOS, with pre-warmed WebRTC live video and two-way audio when supported by the camera.

For installation and setup, see the [project README](../README.md).

## Android

1. **Zero-typing Home Assistant onboarding:** Automatically discovers Home Assistant instances on Wi-Fi via `_home-assistant._tcp` mDNS (storing `external_url` for remote 5G calls and `internal_url` for low-latency LAN calls) and signs in through your browser/Custom Tab using Home Assistant's IndieAuth loopback flow (`http://127.0.0.1:<port>/callback`).
2. **Automatic doorbell discovery & warmup:** Finds doorbell cameras (`binary_sensor.*_doorbell` / `event.*_doorbell` + `camera.*`), sets `preload_stream = false` so Protect flushes an immediate IDR keyframe on play, caches WebRTC ICE server config, and mints a long-lived Home Assistant access token so cold calls skip OAuth refresh round-trips.
3. **Pre-warmed sub-second WebRTC:** When a high-priority FCM push arrives (`RingService`), Doorbell Companion races local LAN and external Home Assistant WebSockets in parallel with local WebRTC SDP offer generation at `t = 0 ms`—before Android finishes opening `CallActivity` on the lock screen.
4. **Two-way audio:** Negotiates `SEND_RECV` audio upfront while keeping the microphone track passive during ringing; tapping **Answer** enables hardware echo-cancelled speakerphone and attaches the microphone immediately whenever the camera's SDP answer supports backchannel audio.

For call startup diagnostics, inspect `adb logcat -s DoorbellTiming:I`.

## iOS

PushKit receives calls from the APNs VoIP relay. CallKit presents the native incoming call screen while the app starts the WebRTC session. Answering opens the snapshot and live video in Doorbell Companion.
