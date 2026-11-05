# Grain Photo

**A separate, native Android recipe camera for your Redmi Note 13 Pro Plus.**

**v0.2.1 — faster, cleaner RAW development:** release build (much faster on the phone), noise curve measured per frame (the phone's own profile is ~5× low), two-scale noise reduction, recipe sharpening, noise-aware grain, and sideways photos saved upright.

**v0.2.0 — first Fujifilm recipe from RAW:** the ◆ **Reggie's Portra** film captures the sensor RAW (plus the phone's own JPEG), then develops it on the phone: Camera2 calibration → scene-linear → F-Log2/F-Gamut → Fujifilm's **official** Classic Chrome LUT (imported by you from Fujifilm's free pack; never bundled) → a stills tone model fitted on public Fujifilm RAF/JPEG pairs → recipe controls → JPEG, with DNG and receipt kept for tuning. See [RAW Fujifilm recipes](docs/RAW_FUJI_RECIPES.md).

**v0.1.1 UI revision:** native layout and controls now follow the final HTML framing edition: custom orange shutter, line icons, anchored ratio/zoom controls, film-card sheet, paper gallery and preferences. See [design parity](docs/DESIGN_PARITY.md).

Cream/olive/orange Grain design. Real Camera2 still capture. Recipes request supported white balance, tone curves, noise reduction and sharpening **before capture**, not a generic color filter added to a finished JPEG. Digital framing is applied afterward as a geometry-only crop.

**Status: device-test beta.** v0.1.1 passed Android compilation, tests, lint and APK packaging in GitHub Actions. v0.1.2 corrects the double sensor-rotation path in the viewfinder; each APK is published only after its CI checks pass. No local Android SDK/build is used. The user confirmed the v0.1.2 viewfinder is upright on the Redmi. v0.1.3 corrects coarse shadow-tone sampling and flags explicitly unfocused captures. Recipe color calibration remains pending.

**v0.1.3 tone correction:** see [sampling diagnosis and remaining limitations](docs/TONE_SAMPLING.md).

**Chroma / Fuji research:** Chroma contains recognizable Fuji-style LUT bases, but not the named recipe catalog sometimes implied by those names. See [research findings and recipe limitations](docs/CHROMA_RESEARCH.md). No third-party LUTs were imported.

## Get your APK with GitHub Actions

This repository now contains the standalone Grain project. The previous project's files were replaced in a normal commit; earlier Git history was preserved.

1. Open **Actions → Build and Release Grain APK** in this repository.
2. A push to `main` starts the build automatically. You can also choose **Run workflow**.
3. After a green run, download the APK from the newest **GitHub Release** (v0.2.1). The Actions artifact **grain-photo-N** is also retained.
4. Install the release APK directly. If using the Actions artifact instead, extract its ZIP first.
5. Allow installation from your browser/file manager when Android asks, then grant **Camera** access.

The Android app's package is `dev.grain.photo`; it installs separately from Zircon Cinema. The final HTML design reference remains under `design/`.

No GitHub secrets are required for a first debug build. No local Flutter/Android Studio/SDK is needed. The workflow installs Java 17, Gradle 8.13, Android API 36 / build-tools 36.0.0 on GitHub's ephemeral runner. It runs tests + lint + assembles an installable release APK signed with the personal debug key.

If CI fails, download the build reports or copy the failing step's logs. A failed run does not publish its APK.

### Updating an installed debug APK

The workflow caches the personal debug signing key; an optional secret provides a more durable key. Cache eviction can still change the key. Android rejects an update signed with a different key. **Export your private photos before uninstalling** if you need to reinstall.

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

- **ISP looks are JPEG; Fujifilm recipes use RAW.** Reggie's Portra needs Camera2 RAW_SENSOR on the rear camera and the imported Fujifilm LUT; Grain explains and refuses to shoot it otherwise. HEIF is not implemented.
- **Full-frame capture is not an unfiltered original.** The ISP recipe is already baked into it. A clean original cannot be recovered from that JPEG.
- **Balanced** chooses the largest public JPEG up to 16 MP; **Larger** up to 24 MP. Digital zoom/cropping reduces output resolution. No claim of access to the stock camera's 200 MP mode.
- The four ISP looks are original experimental 1D RGB responses, not film-stock reproductions. The Fujifilm recipe uses Fujifilm's own LUT, but its stills tone model, grain, Color Chrome, FX Blue, WB-shift step and DR-Auto rule are Grain's approximations and still need testing on the phone.
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
    RawSupport.java              RAW_SENSOR probe, frame + calibration copy, DNG
    RawDevelopment.java          Runs the RAW engine, EXIF-tagged JPEG
    LutLibrary.java              Imports Fujifilm's official F-Log2 LUT zip (private app storage)
    develop/                     Pure-Java RAW engine: demosaic, DNG colour, F-Log2, LUT, tone model
  src/test/                      JUnit math/curve tests
design/grain-prototype.html      Original interactive phone mockup
docs/                           Architecture, source audit, device test checklist
tools/                          SDK-free Java syntax + math smoke checks; DevelopRaw desktop harness
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
