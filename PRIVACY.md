# Doorbell Companion privacy policy

Effective November 26, 2026

Doorbell Companion connects your phone to your Home Assistant installation so you can receive doorbell calls and view and talk to visitors. The app has no separate Doorbell Companion account, ads, or advertising trackers.

## Home Assistant and calls

You sign in on your Home Assistant server. Doorbell Companion stores the connection details, session tokens, doorbell configuration, and preferences on your phone so it can reconnect. It registers your phone with Home Assistant for notifications and configures the doorbell automation there. The push relay does not receive your Home Assistant password or session tokens.

Your phone retrieves camera images and exchanges call audio and video with your Home Assistant setup. The app uses the microphone for talkback when you enable it. The hosted push relay does not receive camera images, video, or microphone audio, and Doorbell Companion does not record calls. Use HTTPS for a Home Assistant server you access over the internet. The app also supports local HTTP connections, which are unencrypted.

## Push notifications

Home Assistant sends a push token and ring details, including the doorbell name, camera entity ID, and timing information, to the relay hosted on Cloudflare. The relay forwards the notification to Firebase Cloud Messaging on Android or Apple Push Notification service on iOS to ring the correct phone. Firebase also uses an installation ID and app and device software information to operate push delivery. See [Firebase's privacy information](https://firebase.google.com/support/privacy/) and [Apple's privacy policy](https://www.apple.com/legal/privacy/).

The relay does not maintain a database of users or ring history. The relay and delivery providers may process network and diagnostic information to operate the service and troubleshoot delivery. The Android relay asks Firebase to hold undelivered messages for up to 30 seconds. For iOS, the relay asks Apple to deliver immediately without queuing. Provider registration identifiers can persist while the service recognizes an installation.

## Disconnecting and privacy requests

Use **Settings → Home Assistant → Disconnect** before uninstalling. Disconnect removes Doorbell Companion's local Home Assistant session and preferences, removes the phone's registration, and revokes the session on Home Assistant. Your Home Assistant account, camera recordings, and other devices are managed in Home Assistant or on your camera system. For help with a privacy or deletion request concerning Doorbell Companion, email [info@nivadema.com](mailto:info@nivadema.com).
