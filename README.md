# Grain Photo

**A separate, native Android recipe camera for your Redmi Note 13 Pro Plus.**

**v0.1.1 UI revision:** native layout and controls now follow the final HTML framing edition: custom orange shutter, line icons, anchored ratio/zoom controls, film-card sheet, paper gallery and preferences. See [design parity](docs/DESIGN_PARITY.md).

Cream/olive/orange Grain design. Real Camera2 still capture. Recipes request supported white balance, tone curves, noise reduction and sharpening **before capture**, not a generic color filter added to a finished JPEG. Digital framing is applied afterward as a geometry-only crop.

**Status: first-device-beta source.** Java syntax and pure geometry/curve math have been checked locally. No Android SDK is installed here, no APK was built here, and no real-phone testing has been performed. GitHub Actions is included for the first complete Android build. Hardware recipe response is deliberately marked uncalibrated.

## Get your APK with GitHub Actions

This repository now contains the standalone Grain project. The previous project's files were replaced in a normal commit; earlier Git history was preserved.

1. Open **Actions → Build and Release Grain APK** in this repository.
2. A push to `main` starts the build automatically. You can also choose **Run workflow**.
3. After a green run, download the APK from the **v0.1.1 GitHub Release**. The Actions artifact **grain-photo-debug-N** is also retained.
4. Extract the artifact ZIP and install `app-debug.apk` on your Redmi Note 13 Pro Plus.
5. Allow installation from your browser/file manager when Android asks, then grant **Camera** access.

The Android app's package is `dev.grain.photo`; it installs separately from Zircon Cinema. The final HTML design reference remains under `design/`.

No GitHub secrets are required for a first debug build. No local Flutter/Android Studio/SDK is needed. The workflow installs Java 17, Gradle 8.13, Android API 36 / build-tools 36.0.0 on GitHub's ephemeral runner. It runs tests + lint + assembles an installable debug APK.

If CI fails, download the build reports or copy the failing step's logs. This project is prepared for CI, not falsely described as a successfully built APK.

### Updating an installed debug APK

A fresh CI runner normally generates a new debug signing key. Android rejects an update signed with a different key. **Export your private photos before uninstalling** if you need to reinstall.

For stable personal updates, optionally generate a standard Android debug keystore on your own machine (`androiddebugkey`, store/key password `android`) and save its base64 encoding as the repository secret `GRAIN_DEBUG_KEYSTORE_BASE64`. The workflow restores it before building. Do not use this debug setup as production signing, and never commit a real private release keystore.

## Included

- Separate package `dev.grain.photo`; installs alongside Zircon Cinema.
- Native Android/Java, no Flutter dependency or WebView wrapper.
- Camera2 JPEG still requests, selected from the phone's public output sizes.
- Four experimental ISP recipes: Daylight, Coast, Golden, Afterhours.
- Per-recipe curve strength, supported WB presets, recipe EV bias and NR/edge preferences.
- Camera capability diagnostics and a per-photo requested/returned-settings receipt.
- Portrait preview, digital pinch zoom, 1×/2×, precise 1–5× slider; no fake optical lens controls.
- Explicit 3:4, 1:1 and 9:16 picker; real output crop, no fixed-size upscaling.
- Tap focus, composition grid, exposure compensation, volume-key shutter.
- Cancellable 3s/5s timer; per-recipe strengths/preferences remembered.
- Optional retained full-frame recipe capture.
- Private gallery, detail view, MediaStore export, sharing, delete with Undo.
- Original interactive HTML design under `design/grain-prototype.html`.

## Important boundaries

- **JPEG only in this beta.** HEIF and RAW/DNG are not implemented. Capability diagnostics can report advertised HEIF, but do not pretend it is enabled.
- **Full-frame capture is not an unfiltered original.** The ISP recipe is already baked into it. A clean original cannot be recovered from that JPEG.
- **Balanced** chooses the largest public JPEG up to 16 MP; **Larger** up to 24 MP. Digital zoom/cropping reduces output resolution. No claim of access to the stock camera's 200 MP mode.
- Recipes are original experimental 1D RGB responses, not calibrated film-stock reproductions or arbitrary 3D LUTs. They need device testing. No grain, halation, Silver monochrome, flash, or RAW engine is silently faked.
- HAL acceptance / metadata does not prove exact pixel response. Check the receipt and run the device test plan.
- Private photos are deleted when the app is uninstalled. Export important photos first. No cloud backup or network access.

## Folder map

```text
.github/workflows/build-apk.yml   GitHub-only APK build
app/                            Android app
  src/main/java/dev/grain/photo/
    MainActivity.java            Native Grain UI and interaction
    GrainStyle.java               Drawn icons, shutter, ratio diagrams, print thumbnail
    GrainCamera.java             Camera2 session / ISP recipe / still capture
    Recipe.java                  Capture-aware recipe definitions
    CurveMath.java               Monotonic experimental channel responses
    Framing.java                 Shared crop/focus/preview geometry
    PhotoStore.java              EXIF/crop, atomic private storage and export
  src/test/                      JUnit math/curve tests
design/grain-prototype.html      Original interactive phone mockup
docs/                           Architecture, source audit, device test checklist
tools/                          SDK-free Java syntax + math smoke checks
```


## Lightweight checks (optional)

On any machine with JDK 17+ and Bash 4+:

```bash
bash tools/check-source.sh
```

This checks Java syntax and 1,227 crop/curve cases without an Android SDK. It does **not** compile/link the Android app. GitHub Actions runs the Android build and JUnit tests.

See [Architecture](docs/ARCHITECTURE.md), [Repository audit](docs/REPO_AUDIT.md), and [Device test plan](docs/DEVICE_TEST_PLAN.md).

## APK release

A successful main-branch build automatically creates/updates the `v0.1.1` prerelease and attaches the debug APK and checksum. It does not publish the old project’s APK. Debug signing is cached between CI builds; supply `GRAIN_DEBUG_KEYSTORE_BASE64` for a durable key independent of cache eviction.
