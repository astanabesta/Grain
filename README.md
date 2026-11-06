# Grain Photo

**A separate, native Android recipe camera for your Redmi Note 13 Pro Plus.**

**v0.3.5 — Sony s709:** Sony's own cinema look ("developed alongside VENICE … subtle colours, smooth colour gradation and a softer low contrast tone curve") as a new film. Download Sony's free `s709Cubes.zip` from Sony's S-Log grading guide and import it like the Fujifilm pack (*Tune → Import s709 zip*); Grain never bundles it. Each photo is developed from the RAW into Sony's S-Log3 / S-Gamut3.Cine with Sony's published formulas, converted exactly for every pixel, then through Sony's table. Grain's own choices: the meter places the scene at Sony's 18 % grey, and the highlight shoulder is stretched where the phone's RAW clips, so clipped areas still reach white. Fujifilm-only settings (Dynamic range, Color Chrome, FX Blue) are not offered for it; white balance, tone, Color, sharpening, noise reduction, Clarity, Grain and the extras work. The Fujifilm films develop exactly as in v0.3.4. See [Sony s709](docs/RAW_FUJI_RECIPES.md#sony-s709-v035).

**v0.3.4 — Face exposure:** Fujifilm's Intelligent Face Detection "sets focus and exposure for human faces", and now a detected face sets the exposure of Grain's Fujifilm-recipe photos too. Before, Grain metered the whole frame, so a window or bright wall behind someone made the face 2–3 stops too dark. Faces come from the camera's face detector or, if it finds none, from the phone's reference JPEG. The face closest to the centre is placed half a stop above the scene key, within +3 / −1.5 EV, and the user's EV is added on top. Backlit faces come out bright and the background may blow out, as on a Fujifilm. It is on by default (Settings → Fujifilm recipes → Face exposure); off, or without a face, photos develop exactly as in v0.3.3. The EXIF Software tag now carries the real version. See [Face exposure](docs/RAW_FUJI_RECIPES.md#face-exposure-v034).

**v0.3.3 — FX Blue that deepens the sky:** Color Chrome FX Blue now does what Fujifilm's does: blue skies get darker and richer at the same hue, like a polarizing filter. It is fitted to Fujifilm X100V camera JPEGs of one clear sky at FX Blue Off, Weak and Strong (Strong takes 9–24 % of the luma off the sky, Weak half of that), and Grain reproduces them within about 2 code values. Before, FX Blue only touched very saturated blues and changed a real sky by less than 0.3 %. It applies to every film and both developments, so Reggie's Superia (Strong) and Reggie's Portra (Weak) have deeper skies; with FX Blue Off every photo develops exactly as in v0.3.2, and Reggie's Portra's grain, texture and other colours are still Grain v0.2.0's. See [FX Blue](docs/RAW_FUJI_RECIPES.md#color-chrome-fx-blue-v033).

**v0.3.2 — Reggie's Superia:** Reggie Ballesteros' new Classic Negative recipe (Fuji X Weekly, March 2026), developed from RAW with Fujifilm's official Classic Neg. LUT at its published settings: DR-Auto, Auto WB R+1 B−3, Highlight −2, Shadow −1, Color +1, Sharpness −2, NR −4, Clarity 0, Grain Strong/Large, Color Chrome Strong, FX Blue Strong, and no typical exposure (at Reggie's request). It is moodier, grainier and more contrasty than his Portra, and is listed right after it. Like Reggie's Portra it starts on *Development: First version*; *Clean* is one tap away in the Grain extras.

**v0.3.1 — Reggie's Portra as in the first version:** Reggie's Portra develops exactly like Grain v0.2.0 again (bit-identical on the test RAW): colour noise reduction only, no sharpening at the recipe's Sharpness −2 and the full grain, so the picture is grittier and more like film. This is the new **Development** setting (Grain extras: *First version* or *Clean*) for every film; Reggie's Portra starts on First version, every other film on Clean, and High ISO NR and Sharpness keep working in both. Everything else from v0.2.1–v0.3.0 stays (speed, upright photos, tuning, white balance, DR-P, extras). See [Development](docs/RAW_FUJI_RECIPES.md#development-first-version-and-clean-v031).

**v0.3.0 — six more Fujifilm recipes:** Kodak Gold 200, Kodachrome 64, Pacific Blues, Vibrant Arizona, CineStill 800T and Kodak Tri-X 400 (Fuji X Weekly), developed from RAW with their published settings as the default and listed right after Reggie's Portra. The settings they need are new for every film: fixed **white balance** (Daylight, Shade, Fluorescent 1–3, Incandescent, Kelvin 2500–10000 K) using Fujifilm's preset white points measured from Fujifilm RAW files, **D-Range Priority** Weak/Strong, and the Acros **+Ye/+R/+G** filters. The recipe's typical exposure compensation is shown as a guide (never applied automatically). Fixed-white-balance films preview with the nearest phone preset. See [RAW Fujifilm recipes](docs/RAW_FUJI_RECIPES.md).

**v0.2.3 — tune any RAW film, plus optional Halation and Bloom:** every ◆ film's settings can be tuned in the film sheet (Dynamic range, WB shift, Highlight, Shadow, Color, Sharpness, High ISO NR, Clarity, Grain, Color Chrome, FX Blue), saved per film, with *Reset this look* back to the default: Fujifilm's standard settings for a film simulation, the published recipe for Reggie's Portra. Clarity is now applied in development. **Halation** and **Bloom** (Off, 1–10) are optional Grain extras, off by default: no Fujifilm film simulation has them. They are made from the RAW light before the film simulation and show in the developed photo only.

**v0.2.2 — every Fujifilm film simulation:** all 10 film simulations in Fujifilm's official LUT pack (Provia, Velvia, Astia, Classic Chrome, Reala Ace, Pro Neg. Std, Classic Neg., Eterna, Eterna Bleach Bypass, Acros) are selectable once the pack is imported, developed from RAW at standard settings. The pack's two Rec.709 video conversions are no longer counted as film simulations.

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
3. After a green run, download the APK from the newest **GitHub Release** (v0.3.2). The Actions artifact **grain-photo-N** is also retained.
4. Install the release APK directly. If using the Actions artifact instead, extract its ZIP first.
5. Allow installation from your browser/file manager when Android asks, then grant **Camera** access.

The Android app's package is `dev.grain.photo`; it installs separately from Zircon Cinema. The final HTML design reference remains under `design/`.

No GitHub secrets are required for a first debug build. No local Flutter/Android Studio/SDK is needed. The workflow installs Java 17, Gradle 8.13, Android API 36 / build-tools 36.0.0 on GitHub's ephemeral runner. It runs tests + lint + assembles an installable release APK signed with the personal debug key.

If CI fails, download the build reports or copy the failing step's logs. A failed run does not publish its APK.

### Updating an installed APK

Releases up to v0.2.0 were each signed with a fresh key (the old key cache never kept the key), so every update needed an uninstall. From v0.2.1 the workflow creates one personal signing key on `main`, keeps it in the Actions cache (every build refreshes it) and signs every release with it, so updates install over the previous version. GitHub evicts caches that go unused for 7 days; after a longer gap without builds the key would be recreated and one more uninstall would be needed. **Save your photos to the device gallery before uninstalling.**

Personal signing certificate from v0.2.1 (SHA-256): `6e073ef68730c0022f8359b668bbfc087e8ec348ad334a1afb962ad06e206553`. An APK signed with any other certificate will not install over this one.

For a key that never changes, generate a keystore (`androiddebugkey`, store/key password `android`) and save its base64 encoding as the repository secret `GRAIN_DEBUG_KEYSTORE_BASE64`; the workflow then signs with it instead (switching to it changes the key once). This is personal test signing, not production signing; never commit a keystore.

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
- The four ISP looks are original experimental 1D RGB responses, not film-stock reproductions. The Fujifilm recipes use Fujifilm's own LUTs, but the stills tone model, grain, Color Chrome, FX Blue, WB-shift step, DR-Auto rule, D-Range Priority curve and Acros filters are Grain's approximations, and the fixed white-balance presets are measured values with about ±150 K uncertainty; all still need testing on the phone.
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

This checks Java syntax and 1,230 crop/curve/preview cases without an Android SDK. It does **not** compile/link the Android app. GitHub Actions runs the Android build and JUnit tests.

See [Architecture](docs/ARCHITECTURE.md), [Repository audit](docs/REPO_AUDIT.md), and [Device test plan](docs/DEVICE_TEST_PLAN.md).

## APK release

A successful main-branch build automatically creates/updates the `v0.1.1` prerelease and attaches the debug APK and checksum. It does not publish the old project’s APK. Debug signing is cached between CI builds; supply `GRAIN_DEBUG_KEYSTORE_BASE64` for a durable key independent of cache eviction.
