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

## Signing

CI signs the release APK with a throwaway key generated per build, so the APK is
installable but **every build has a different signature** — installing a new one
over an old one requires uninstalling first. For a Play Store release, replace
that with a real keystore in repository secrets.
