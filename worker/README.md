# Doorbell Companion Push Relay Worker

Stateless Cloudflare Worker that relays Home Assistant doorbell notifications for Doorbell Companion. Android uses FCM through `/push`, and iOS uses APNs VoIP through `/push/ios`.

The `/android` and `/ios` pages declare the direct app callback (`doorbell-companion://auth-callback`) for Home Assistant sign-in. Push responses include `Server-Timing` measurements for auth token acquisition and delivery, marking cache hits/misses on `fcm_auth` (`/push`) and `apns_auth` (`/push/ios`).

Home Assistant passes each device's `push_token` (an FCM registration token on `POST /push` or an environment-prefixed APNs VoIP token on `POST /push/ios`) directly in the request body, so a single deployed Worker serves any number of Home Assistant installations with zero database or stored user state.

> [!NOTE]
> Before deploying to your own Cloudflare account, remove or replace `account_id` in [`wrangler.jsonc`](wrangler.jsonc).

## Android setup

1. Register the Android app `nl.niek.doorbellcompanion` in your Firebase project and put its `google-services.json` in `app/android/app/`. Enable the FCM API and obtain a service account credential with permission to send FCM messages.
2. Set the secret and deploy:

   ```sh
   npx wrangler secret put FIREBASE_SERVICE_ACCOUNT < /path/to/service-account.json
   npx wrangler deploy
   ```

3. Point `RELAY_BASE_URL` in `app/android/app/src/main/kotlin/nl/niek/doorbellcompanion/HomeAssistantAuth.kt` to `https://YOUR-WORKER.workers.dev` (or use the hosted default). Signing in to Home Assistant in Doorbell Companion sets up automatic ring notifications.

## iOS VoIP pushes

Create an APNs signing key for the Doorbell Companion VoIP topic. Store a JSON secret named `APNS_SANDBOX_AUTH` for Debug builds and `APNS_PRODUCTION_AUTH` for Release builds. The JSON fields are `team_id`, `key_id`, and `private_key`. The last field is the full PEM contents of the `.p8` key. An existing key that supports both environments can be stored as `APNS_AUTH`.

```sh
npx wrangler secret put APNS_SANDBOX_AUTH < /path/to/apns-sandbox-auth.json
npx wrangler deploy
```

The iOS app registers with Home Assistant against `/push/ios`. The relay sends the PushKit token to Apple using the `<bundle-id>.voip` topic. Keep `APNS_BUNDLE_ID` aligned with the iOS bundle ID if you override the default `nl.niek.doorbellcompanion`. For a self-hosted relay, also point `DoorbellCompanionRelayURL` in the iOS `Info.plist` at the HTTPS origin of your Worker.

Use a deployed Worker when testing APNs delivery. Apple requires HTTP/2, which the local Workers runtime may not negotiate with APNs.
