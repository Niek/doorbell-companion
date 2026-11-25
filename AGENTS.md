# Doorbell Companion

Doorbell Companion handles incoming doorbell calls from Home Assistant on Android and iOS, including doorbells connected through UniFi Protect. Setup and device requirements are in the [project README](README.md).

## Repository layout

- [`app/lib/`](app/lib/) contains the shared Flutter UI.
- [`app/android/`](app/android/) contains the native Kotlin implementation for FCM, full-screen calls, and camera playback.
- [`app/ios/Runner/`](app/ios/Runner/) contains the native Swift implementation for PushKit, CallKit, and camera playback.
- [`worker/`](worker/) contains the stateless Cloudflare push relay. It serves `/push` for Android FCM, `/push/ios` for iOS VoIP pushes, and `/android` and `/ios` for Home Assistant sign-in.

The Android application ID and iOS bundle ID are both `nl.niek.doorbellcompanion`.

## Local checks

- From `app/`, run `flutter pub get` and `flutter analyze`. Match the Flutter version in the Android workflow when reproducing CI.
- From `app/`, run `flutter build apk --release` for Android or `flutter build ios --simulator` on macOS for an iOS compile check. Use a signed iPhone build to verify APNs VoIP delivery and CallKit.
- In the app, use **Test call** for camera and audio, then **Test HA push (3s delay)** with the phone locked to verify the Home Assistant push path.
- From `worker/`, run `npx wrangler deploy --dry-run` to check the relay bundle locally.

## GitHub Actions

Pushes to `main` trigger both workflows, including documentation-only pushes.

- [Android APK](.github/workflows/android-apk.yml) builds a release-mode APK and uploads `app-release.apk` as an artifact on the workflow run.
- [Deploy Worker](.github/workflows/worker-deploy.yml) runs Wrangler from `worker/` and deploys the production `doorbell-companion` Worker. The workflow uses the Worker-scoped GitHub Actions secret `CLOUDFLARE_WORKER_DEPLOY_TOKEN`. Firebase and APNs credentials are runtime secrets in Cloudflare. [Relay setup](worker/README.md)

## Upload an Android internal test build

1. In [`app/pubspec.yaml`](app/pubspec.yaml), set the number after `+` higher than any version code already uploaded to Play. For example, after version code `1`, use `1.0.1+2` for a patch release.
2. Build with the existing Play upload key. Gradle reads `app/android/key.properties`, which points to the local `app/android/upload-keystore.jks`. Both files are Git-ignored. [Flutter signing setup](https://docs.flutter.dev/deployment/android#sign-the-app)
3. From `app/`, run `flutter build appbundle --release`. The signed bundle is `app/build/app/outputs/bundle/release/app-release.aab` relative to the repo root.
4. Open [Doorbell Companion in Play Console](https://play.google.com/console/u/0/developers/6626168487007018049/app/4973999367364194332/app-dashboard). Go to **Test and release → Testing → Internal testing → Create new release**. Upload the `.aab`, add release notes, wait for Play to process the bundle, then select **Next → Save and publish** and confirm. If an automated file upload fails, select the bundle through Chrome’s native file picker.
5. Verify that Play Console shows the new version as available to internal testers and that the **Niek** group is selected on the **Testers** tab. Testers can join with their listed Google account through the [internal test invite](https://play.google.com/apps/internaltest/4700516454089035458) and install or update from Google Play.
