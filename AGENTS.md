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

### Test with the Home Assistant demo

Use the isolated Fly Home Assistant instance for sign-in, camera playback, and push checks. The Git-ignored `.env` at the repository root contains `REVIEW_HA_URL`, `REVIEW_HA_USERNAME`, and `REVIEW_HA_PASSWORD`. Use these values for the app's Home Assistant sign-in and keep the password out of logs, screenshots, and shared notes. The password is also stored in macOS Keychain under service `doorbell-companion-review` and account `doorbell-reviewer`.

1. On a test device, connect to `REVIEW_HA_URL` and sign in with the demo credentials. If the first connection times out, allow up to a minute for Fly to start and retry.
2. Open **View** on **Demo Doorbell** and check that the snapshot loads and the silent video loop plays.
3. On a physical phone, allow notifications, tap **Test ring**, and lock the phone during the three-second countdown. Confirm that the incoming call appears on the locked screen. Use a signed iPhone build for the APNs VoIP and CallKit check.

## GitHub Actions

Pushes to `main` trigger both workflows, including documentation-only pushes.

- [Android APK](.github/workflows/android-apk.yml) builds a release-mode APK and uploads `app-release.apk` as an artifact on the workflow run.
- [Deploy Worker](.github/workflows/worker-deploy.yml) runs Wrangler from `worker/` and deploys the production `doorbell-companion` Worker. The workflow uses the Worker-scoped GitHub Actions secret `CLOUDFLARE_WORKER_DEPLOY_TOKEN`. Firebase and APNs credentials are runtime secrets in Cloudflare. [Relay setup](worker/README.md)

## Release a new version

When asked to **release a new version**, publish to **Internal testing** and submit to **Open testing** on Google Play. On TestFlight, distribute the build to **Internal** and **Private Beta**. Follow both platform sections below unless the request specifies a different scope.

Set the version and build number once in [`app/pubspec.yaml`](app/pubspec.yaml) and build both apps from the same checkout. For a new release, choose a number after `+` higher than every version code already uploaded to Google Play and every build number already uploaded for the same iOS version.

Verify the exact version and build number on every track and group. If a track or group already has the intended build, continue with the remaining destinations. Report submissions awaiting review separately from builds available to testers.

### Publish an Android test build

Install `gplay` with `brew install PollyGlot/tap/gplay`. The `doorbell-play-publisher` profile uses a key stored in macOS Keychain and has testing release access for Doorbell Companion. Run `gplay auth status --account doorbell-play-publisher` to check that the backend is `keyring`. On a new Mac, register the key with `gplay auth login --name doorbell-play-publisher --service-account /path/to/key.json`. [gplay authentication](https://gplay.sh/docs/concepts/authentication/)

1. From `app/`, run `flutter build appbundle --release` with the existing Play upload key. Gradle reads `app/android/key.properties`, which points to the local `app/android/upload-keystore.jks`. Both files are Git-ignored. [Flutter signing setup](https://docs.flutter.dev/deployment/android#sign-the-app)
2. From the repo root, publish the signed bundle to Internal testing. Replace the example release notes with the changes in this build.

   ```sh
   gplay releases upload app/build/app/outputs/bundle/release/app-release.aab \
     --account doorbell-play-publisher --package nl.niek.doorbellcompanion \
     --track internal --complete --release-notes 'Describe what changed in this build.'
   ```

3. From the repo root, promote the same version code to Open testing (`beta`). This uses the bundle and release notes already on Internal testing.

   ```sh
   version_code=$(awk -F+ '/^version: / {print $2}' app/pubspec.yaml)
   gplay releases promote --account doorbell-play-publisher \
     --package nl.niek.doorbellcompanion --from internal --to beta \
     --version-code "$version_code" --complete
   ```

4. Read both tracks back from Google Play. Verify the version code on each track and confirm Internal testing is `completed`. Check **Publishing overview** in Play Console for the Open testing release. Submit it if Play is waiting for **Send for review**, and report whether it is in review, ready to publish, or available to testers. [Google Play publishing guide](https://support.google.com/googleplay/android-developer/answer/9859654)

   ```sh
   gplay tracks list --account doorbell-play-publisher \
     --package nl.niek.doorbellcompanion \
     --output table --columns track,release,status,versionCodes
   ```

   To verify delivery, install or update through Google Play with an account in the **Niek** tester group and confirm the installed build. The update can take a few minutes to appear after Play reports `completed`. [Internal test invite](https://play.google.com/apps/internaltest/4700516454089035458)

### Publish an iOS TestFlight build

Use the **Doorbell Companion TestFlight** App Store Connect API key for **Nivadema B.V.** (`N577R5DT64`). The local `~/.config/doorbell-companion/testflight/config` sets `ASC_KEY_ID` and `ASC_ISSUER_ID`. Keep `AuthKey_<key ID>.p8` in that same directory with owner-only permissions. Xcode uses the key for provisioning and upload without an interactive Xcode sign-in. Doorbell Companion's explicit App ID has Push Notifications enabled.

1. From `app/`, prepare the Flutter configuration and archive the release with Xcode.

   ```sh
   testflight_config_dir="$HOME/.config/doorbell-companion/testflight"
   . "$testflight_config_dir/config" &&
   flutter build ios --release --config-only --no-codesign &&
   xcodebuild archive -workspace ios/Runner.xcworkspace -scheme Runner \
     -configuration Release -destination 'generic/platform=iOS' \
     -archivePath build/ios/archive/DoorbellCompanion.xcarchive \
     -allowProvisioningUpdates \
     -authenticationKeyPath "$testflight_config_dir/AuthKey_${ASC_KEY_ID}.p8" \
     -authenticationKeyID "$ASC_KEY_ID" \
     -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
     DEVELOPMENT_TEAM=N577R5DT64 CODE_SIGN_STYLE=Automatic
   ```

2. From `app/`, upload the signed archive using [`app/ios/TestFlightExportOptions.plist`](app/ios/TestFlightExportOptions.plist). These options allow external TestFlight distribution and preserve the version and build number from `pubspec.yaml`.

   ```sh
   testflight_config_dir="$HOME/.config/doorbell-companion/testflight"
   . "$testflight_config_dir/config" &&
   xcodebuild -exportArchive \
     -archivePath build/ios/archive/DoorbellCompanion.xcarchive \
     -exportPath build/ios/testflight \
     -exportOptionsPlist ios/TestFlightExportOptions.plist \
     -allowProvisioningUpdates \
     -authenticationKeyPath "$testflight_config_dir/AuthKey_${ASC_KEY_ID}.p8" \
     -authenticationKeyID "$ASC_KEY_ID" \
     -authenticationKeyIssuerID "$ASC_ISSUER_ID"
   ```

3. After Apple processes the upload, add the exact build to **Doorbell Companion → TestFlight → Private Beta → Builds** in App Store Connect with an Account Holder, Admin, or App Manager account. Enter **What to Test**, enable **Automatically notify testers**, then choose **Submit Review** or **Start Testing**, depending on the build's status. Check the exact build's status for **Private Beta** and report whether it is awaiting review or available. [Apple’s external testing guide](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers/)

4. The **Internal** group automatically distributes eligible builds. Open the native **TestFlight** app on the Mac with an account in that group. Under **Doorbell Companion → Previous Builds**, select the version from `pubspec.yaml` and confirm the exact build is available to install or is already installed. [Apple’s internal testing guide](https://developer.apple.com/help/app-store-connect/test-a-beta-version/add-internal-testers/)
