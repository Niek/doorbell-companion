# Doorbell Companion review server

This Fly app runs one Home Assistant Container in Amsterdam on a dedicated private network. The encrypted `ha_data` volume keeps `/config` across restarts. The machine starts on HTTP traffic and stops when idle.

From this directory, redeploy the existing app with the local Docker engine.

```sh
flyctl deploy --ha=false --no-public-ips --local-only --strategy immediate --wait-timeout 50s
```

For a new app, create it on a dedicated network and deploy without a public IP. Finish Home Assistant onboarding over `flyctl proxy 18124:8123 --app doorbell-companion-review` at `http://127.0.0.1:18124` before assigning a shared public IPv4 address. The Dockerfile seeds the Home Assistant configuration only on an empty volume.

## Tester access

Use `https://doorbell-companion-review.fly.dev` as the Home Assistant URL in the app. The `doorbell-reviewer` account is a non-owner Home Assistant administrator because the app saves an automation during setup. Give the password only to reviewers using the private review notes. Retrieve it from Keychain on the Mac that provisioned the account.

```sh
security find-generic-password -a doorbell-reviewer -s doorbell-companion-review -w
```

Tap **View** on **Demo Doorbell** to see the silent sample video. Allow notifications, then use **Test ring** and lock the phone during the three-second countdown. The test ring is sent to the phone running the test. If the machine has stopped while idle, give the first connection time to wake it.

## Camera fixture

The image generates a synthetic JPEG and a looping H.264 clip. To restore the video fixture on a fresh Home Assistant volume, add **Generic Camera** with `http://127.0.0.1:1984/review_doorbell.jpg` as its still image and `rtsp://127.0.0.1:8554/review_doorbell` as its stream source. Use TCP for RTSP and name the entity **Demo Doorbell**. The camera endpoints listen on loopback inside the machine and Home Assistant handles authenticated access. For a static fallback, add **Local file** with `/media/review_doorbell.jpg`.

Fly’s shared IPv4 supports HTTPS. For inbound UDP, Fly requires a dedicated IPv4. The shared IPv4 in `configuration.yaml` is the one assigned to this app. Update it if the address changes.
