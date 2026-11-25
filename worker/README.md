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
