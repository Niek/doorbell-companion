# Doorbell Companion Push Relay Worker

Stateless Cloudflare Worker that relays Home Assistant doorbell notifications for Doorbell Companion. Android uses FCM through `/push`, and iOS uses APNs VoIP through `/push/ios`.

Home Assistant passes each user's Firebase registration token (`push_token`) directly in the `POST /push` request body, so a single deployed Worker serves any number of Home Assistant installations with zero database or stored user state.

## Android setup

1. Register the Android app `nl.niek.doorbellcompanion` in your Firebase project and put its `google-services.json` in `app/android/app/`. Enable the FCM API and obtain a service account credential with permission to send FCM messages.
2. Set the secret and deploy:

   ```sh
   npx wrangler secret put FIREBASE_SERVICE_ACCOUNT < /path/to/service-account.json
   npx wrangler deploy
   ```

3. Point `PUSH_RELAY_URL` in `app/android/app/src/main/kotlin/nl/niek/doorbellcompanion/HomeAssistantAuth.kt` to `https://YOUR-WORKER.workers.dev/push` (or use the hosted default). Signing in to Home Assistant in Doorbell Companion sets up automatic ring notifications.

## iOS VoIP pushes

Create an APNs signing key for the Doorbell Companion VoIP topic. Store a JSON secret named `APNS_SANDBOX_AUTH` for Debug builds and `APNS_PRODUCTION_AUTH` for production builds. The JSON fields are `team_id`, `key_id`, and `private_key`. The last field is the full PEM contents of the `.p8` key. An existing key that supports both environments can be stored as `APNS_AUTH`.

```sh
npx wrangler secret put APNS_SANDBOX_AUTH < /path/to/apns-sandbox-auth.json
npx wrangler deploy
```

The iOS app registers with Home Assistant against `/push/ios`. The relay sends the PushKit token to Apple using the `<bundle-id>.voip` topic. Keep `APNS_BUNDLE_ID` aligned with the iOS bundle ID if you override the default `nl.niek.doorbellcompanion`. For a self-hosted relay, also point `DoorbellCompanionRelayURL` in the iOS `Info.plist` at the HTTPS origin of your Worker.

Use a deployed Worker when testing APNs delivery. Apple requires HTTP/2, which the local Workers runtime may not negotiate with APNs.
