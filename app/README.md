# Doorbell Companion

Fast incoming doorbell calls for Home Assistant on Android and iOS, with pre-warmed WebRTC live video and two-way audio when supported by the camera.

For installation and setup, see the [project README](../README.md).

## Android

1. **Zero-typing Home Assistant onboarding:** Discovers Home Assistant over Wi-Fi and signs in through your browser. Home Assistant instances that advertise PKCE show the hosted Doorbell Companion URL and return directly to the app. **Use local sign-in** is available if Home Assistant cannot reach the hosted page.
2. **Automatic doorbell discovery & warmup:** Finds doorbell cameras (`binary_sensor.*_doorbell` / `event.*_doorbell` + `camera.*`), sets `preload_stream = false` so Protect flushes an immediate IDR keyframe on play, and caches WebRTC ICE configuration. Expired OAuth tokens refresh over the first available Home Assistant connection while the call starts.
3. **Pre-warmed sub-second WebRTC:** When a high-priority FCM push arrives (`RingService`), Doorbell Companion races local LAN and external Home Assistant WebSockets in parallel with local WebRTC SDP offer generation at `t = 0 ms`—before Android finishes opening `CallActivity` on the lock screen.
4. **Two-way audio:** Negotiates `SEND_RECV` audio upfront while keeping the microphone track passive during ringing; tapping **Answer** enables hardware echo-cancelled speakerphone and attaches the microphone immediately whenever the camera's SDP answer supports backchannel audio.

For call startup diagnostics, inspect `adb logcat -s DoorbellTiming:I`.

## iOS

PushKit receives calls from the APNs VoIP relay. CallKit presents the native incoming call screen while the app starts the WebRTC session. Answering opens the snapshot and live video in Doorbell Companion.
