# Protect Fast Push Relay Worker

Stateless Cloudflare Worker acting as the Home Assistant `mobile_app` `push_url` relay (`POST /push`) for Protect Fast.

Home Assistant passes each user's Firebase registration token (`push_token`) directly in the `POST /push` request body, so a single deployed Worker serves any number of Home Assistant installations with zero database or stored user state.

## Setup

1. Create a Firebase project for `nl.niek.protect_fast`, enable the FCM API, and download a service account JSON key with permission to send FCM messages.
2. Set the secret and deploy:

   ```sh
   npx wrangler secret put FIREBASE_SERVICE_ACCOUNT < /path/to/service-account.json
   npx wrangler deploy
   ```

3. Point `PUSH_RELAY_URL` in `app/android/app/src/main/kotlin/nl/niek/protect_fast/HomeAssistantAuth.kt` to `https://YOUR-WORKER.workers.dev/push` (or use the hosted default). Signing in to Home Assistant in Protect Fast sets up automatic ring notifications.

## iOS VoIP pushes

Create an APNs signing key for the Protect Fast VoIP topic. Store a JSON secret named `APNS_SANDBOX_AUTH` for Debug builds and `APNS_PRODUCTION_AUTH` for production builds. The JSON fields are `team_id`, `key_id`, and `private_key`. The last field is the full PEM contents of the `.p8` key. An existing key that supports both environments can be stored as `APNS_AUTH`.

```sh
npx wrangler secret put APNS_SANDBOX_AUTH < /path/to/apns-sandbox-auth.json
npx wrangler deploy
```

The iOS app registers with Home Assistant against `/push/ios`. The relay sends the PushKit token to Apple using the `<bundle-id>.voip` topic. Keep `APNS_BUNDLE_ID` aligned with the iOS bundle ID if you override the default `nl.niek.protect-fast`. For a self-hosted relay, also point `ProtectFastRelayURL` in the iOS `Info.plist` at the HTTPS origin of your Worker.

Use a deployed Worker when testing APNs delivery. Apple requires HTTP/2, which the local Workers runtime may not negotiate with APNs.
