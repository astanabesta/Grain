# Recipe Cam

A camera app for Fujifilm film-simulation recipes: pick a recipe, shoot, and the
frame already looks that way — SOOC, no editing.

- `web/index.html` — the whole app in one self-contained file (no network, no CDN)
- `app-shell/` — Capacitor wrapper that packages it as an Android app
- `.github/workflows/android.yml` — CI that builds the APK

## Get the APK

Every push to `main` publishes a rolling **release** with `app-*.apk` attached.
Open the Releases page, download the APK, and install it (Android will warn about
an unknown developer — expected for a sideloaded build).

Tagging `v1.0.0` produces a versioned release instead.

## Build locally

```bash
cd app-shell
npm ci
npx cap add android
npx cap sync android
cd android && ./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/`.

## Editing the web app

Build the single file from the design sources, then copy it into place:

```bash
OUT=recipe-cam-design-v3.html DESIGN_DIR=design/v3 python3 design/build.py
cp recipe-cam-design-v3.html web/index.html
```

The CI check fails if `web/index.html` ever references an external `<script src>`
or `<link href>`, because that would silently break the offline app.

## Camera permissions

The camera is not a nice-to-have here, so it is worth knowing what makes it work:

- `AndroidManifest.xml` must **declare** `android.permission.CAMERA`. Capacitor's
  `BridgeWebChromeClient` maps WebView `VIDEO_CAPTURE` to a runtime request, but a
  package that does not declare the permission is refused *before any dialog
  appears*, and `getUserMedia` reports `NotAllowedError` — indistinguishable from
  the user tapping "deny". CI injects the declaration after `cap add android` and
  then asserts it landed.
- `@capacitor/filesystem` + `@capacitor/share` are required for saving. A blob
  `<a download>` does nothing inside a WebView, so the app writes the JPEG and
  opens the system share sheet instead.
- The app opens the camera on launch, releases the sensor when backgrounded, and
  falls back to the bundled sample frame if permission is refused.

**Not verified by CI:** the APK is built but never launched, so the camera path is
not exercised on a device. It is checked in a desktop browser against Chromium's
fake capture device. If a device-specific failure shows up, the app renders the
error and user agent on screen instead of a black rectangle.

## Signing

CI signs the release APK with a throwaway key generated per build, so the APK is
installable but **every build has a different signature** — installing a new one
over an old one requires uninstalling first. For a Play Store release, replace
that with a real keystore in repository secrets.
