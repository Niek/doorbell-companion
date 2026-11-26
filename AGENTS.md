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
- In the app, use **View** on a doorbell for camera and audio, then **Test ring** with the phone locked to verify the Home Assistant push path.
- From `worker/`, run `npx wrangler deploy --dry-run` to check the relay bundle locally.

## GitHub Actions

Pushes to `main` trigger both workflows, including documentation-only pushes.

- [Android APK](.github/workflows/android-apk.yml) builds a release-mode APK and uploads `app-release.apk` as an artifact on the workflow run.
- [Deploy Worker](.github/workflows/worker-deploy.yml) runs Wrangler from `worker/` and deploys the production `doorbell-companion` Worker. The workflow uses the Worker-scoped GitHub Actions secret `CLOUDFLARE_WORKER_DEPLOY_TOKEN`. Firebase and APNs credentials are runtime secrets in Cloudflare. [Relay setup](worker/README.md)

## Publish an Android internal test build

Install `gplay` with `brew install PollyGlot/tap/gplay`. The `doorbell-play-publisher` profile uses a key stored in macOS Keychain and has testing release access for Doorbell Companion. Run `gplay auth status --account doorbell-play-publisher` to check that the backend is `keyring`. On a new Mac, register the key with `gplay auth login --name doorbell-play-publisher --service-account /path/to/key.json`. [gplay authentication](https://gplay.sh/docs/concepts/authentication/)

1. In [`app/pubspec.yaml`](app/pubspec.yaml), set the number after `+` higher than any version code already uploaded to Google Play.
2. From `app/`, run `flutter build appbundle --release` with the existing Play upload key. Gradle reads `app/android/key.properties`, which points to the local `app/android/upload-keystore.jks`. Both files are Git-ignored. [Flutter signing setup](https://docs.flutter.dev/deployment/android#sign-the-app)
3. From the repo root, publish the signed bundle to Internal testing. Replace the example release notes with the changes in this build.

   ```sh
   gplay releases upload app/build/app/outputs/bundle/release/app-release.aab \
     --account doorbell-play-publisher --package nl.niek.doorbellcompanion \
     --track internal --complete --release-notes 'Describe what changed in this build.'
   ```

4. Read the Internal testing track back from Google Play. Verify that the row with the new version code from `pubspec.yaml` has status `completed`.

   ```sh
   gplay releases list --account doorbell-play-publisher \
     --package nl.niek.doorbellcompanion --track internal \
     --output table --columns name,status,versionCodes
   ```

   To verify delivery, install or update through Google Play with an account in the **Niek** tester group and confirm the installed build. The update can take a few minutes to appear after Play reports `completed`. [Internal test invite](https://play.google.com/apps/internaltest/4700516454089035458)

## Publish an iOS internal TestFlight build

Sign in to the **Nivadema B.V.** team (`N577R5DT64`) in **Xcode → Settings → Accounts**. Xcode uses that account for automatic signing and upload. Doorbell Companion's explicit App ID has Push Notifications enabled. [App Store Connect](https://appstoreconnect.apple.com/apps/6839501329/distribution)

1. In [`app/pubspec.yaml`](app/pubspec.yaml), use a build number after `+` higher than any build already uploaded for that iOS version.
2. From `app/`, prepare the Flutter configuration and archive the release with Xcode.

   ```sh
   flutter build ios --release --config-only --no-codesign
   xcodebuild archive -workspace ios/Runner.xcworkspace -scheme Runner \
     -configuration Release -destination 'generic/platform=iOS' \
     -archivePath build/ios/archive/DoorbellCompanion.xcarchive \
     -allowProvisioningUpdates DEVELOPMENT_TEAM=N577R5DT64 CODE_SIGN_STYLE=Automatic
   ```

3. From `app/`, upload the signed archive for internal TestFlight distribution. The export options preserve the build number from `pubspec.yaml`.

   ```sh
   xcodebuild -exportArchive \
     -archivePath build/ios/archive/DoorbellCompanion.xcarchive \
     -exportPath build/ios/testflight \
     -exportOptionsPlist ios/TestFlightExportOptions.plist \
     -allowProvisioningUpdates
   ```

4. After Apple processes the upload, open TestFlight with an account in the **Internal** group. Confirm the exact version and build number appear, then install or update. The group automatically distributes eligible builds. [TestFlight processing and internal testing](https://developer.apple.com/help/app-store-connect/test-a-beta-version/add-internal-testers/)
